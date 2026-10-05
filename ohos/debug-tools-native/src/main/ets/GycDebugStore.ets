import { common } from '@kit.AbilityKit';
import { preferences } from '@kit.ArkData';
import { huks } from '@kit.UniversalKeystoreKit';
import { cryptoFramework } from '@kit.CryptoArchitectureKit';
import { util } from '@kit.ArkTS';

export interface GycDebugStoreOptions {
  namespace: string;
  keyAlias: string;
  tokenKey: string;
  shakeEnabledKey: string;
  historyKey: string;
  draftKey: string;
  pendingKey: string;
}
interface EncryptedToken { nonce: string; cipher: string; }

/** 同一 HUKS alias 的生成/读写/清理串行；Token 不落明文，普通记录的写操作等待 flush。 */
export class GycDebugStore {
  private static queues: Map<string, Promise<void>> = new Map();
  private readonly encoder = new util.TextEncoder();
  private readonly base64 = new util.Base64Helper();
  private readonly context: common.UIAbilityContext;
  private readonly options: GycDebugStoreOptions;
  constructor(context: common.UIAbilityContext, options: GycDebugStoreOptions) {
    this.context = context; this.options = options;
    const keys = [options.namespace, options.keyAlias, options.tokenKey, options.shakeEnabledKey,
      options.historyKey, options.draftKey, options.pendingKey];
    keys.forEach((key: string) => {
      if (!/^[A-Za-z0-9_.-]{1,128}$/.test(key)) throw new Error('Invalid debug store key');
    });
    if (new Set(keys.slice(2)).size !== 5) throw new Error('Debug store keys must be distinct');
  }

  readToken(active: () => boolean = (): boolean => true): Promise<string> { return this.serial(async (): Promise<string> => {
    const prefs = await this.prefs();
    const stored = await prefs.get(this.options.tokenKey, '');
    if (typeof stored !== 'string') throw new Error('Invalid token storage');
    if (stored.length === 0) return '';
    const payload = JSON.parse(stored) as EncryptedToken;
    const nonce = this.base64.decodeSync(payload.nonce);
    const cipher = this.base64.decodeSync(payload.cipher);
    if (nonce.length !== 12 || cipher.length < 16) throw new Error('Invalid encrypted token');
    const decrypted = await this.crypt(cipher, nonce, false);
    // 临时 HUKS 服务错误不删除凭据或降级明文；宿主可明确清除/重新授权。
    return new util.TextDecoder('utf-8', { fatal: true }).decodeWithStream(decrypted);
  }, active); }

  writeToken(token: string, active: () => boolean = (): boolean => true): Promise<void> { return this.serial(async (): Promise<void> => {
    if (token.length === 0 || token.length > 16384) throw new Error('Invalid token length');
    const generation: huks.HuksOptions = { properties: this.keyProperties() };
    if (!await huks.isKeyItemExist(this.options.keyAlias, generation)) {
      await huks.generateKeyItem(this.options.keyAlias, generation);
    }
    const nonce = cryptoFramework.createRandom().generateRandomSync(12).data;
    const encrypted = await this.crypt(this.encoder.encodeInto(token), nonce, true);
    const payload: EncryptedToken = {
      nonce: this.base64.encodeToStringSync(nonce), cipher: this.base64.encodeToStringSync(encrypted)
    };
    const prefs = await this.prefs();
    this.checkActive(active);
    await prefs.put(this.options.tokenKey, JSON.stringify(payload));
    await prefs.flush();
  }, active); }

  clearCredentials(active: () => boolean = (): boolean => true): Promise<void> { return this.serial(async (): Promise<void> => {
    const prefs = await this.prefs();
    this.checkActive(active);
    await prefs.delete(this.options.tokenKey);
    await prefs.flush();
  }, active); }

  read(method: string, active: () => boolean = (): boolean => true): Promise<string> { return this.serial(async (): Promise<string> => {
    const key = this.key(method);
    const fallback = method === 'readShakeEnabled' ? 'true' : method === 'readDraft' ? '{}' : '[]';
    const result = await (await this.prefs()).get(key, fallback);
    if (typeof result !== 'string') throw new Error('Invalid debug record');
    return result;
  }, active); }

  write(method: string, value: string, active: () => boolean = (): boolean => true): Promise<void> { return this.serial(async (): Promise<void> => {
    if (value.length > 1048576) throw new Error('Debug record too large');
    const key = this.key(method);
    JSON.parse(value);
    const prefs = await this.prefs();
    this.checkActive(active);
    await prefs.put(key, value);
    await prefs.flush();
  }, active); }

  private key(method: string): string {
    switch (method) {
      case 'readShakeEnabled': case 'writeShakeEnabled': return this.options.shakeEnabledKey;
      case 'readHistory': case 'writeHistory': return this.options.historyKey;
      case 'readDraft': case 'writeDraft': return this.options.draftKey;
      case 'readPending': case 'writePending': return this.options.pendingKey;
      default: throw new Error('Unsupported store operation');
    }
  }
  private prefs(): Promise<preferences.Preferences> {
    return preferences.getPreferences(this.context, { name: this.options.namespace });
  }
  private serial<T>(operation: () => Promise<T>, active: () => boolean): Promise<T> {
    const key = this.options.keyAlias;
    const previous = GycDebugStore.queues.get(key) ?? Promise.resolve();
    const result = previous.then((): Promise<T> => { this.checkActive(active); return operation(); });
    const tail = result.then((): void => {}, (): void => {});
    GycDebugStore.queues.set(key, tail);
    tail.then((): void => {
      if (GycDebugStore.queues.get(key) === tail) GycDebugStore.queues.delete(key);
    });
    return result;
  }
  private checkActive(active: () => boolean): void { if (!active()) throw new Error('Debug request cancelled'); }
  private keyProperties(): huks.HuksParam[] {
    return [
      { tag: huks.HuksTag.HUKS_TAG_ALGORITHM, value: huks.HuksKeyAlg.HUKS_ALG_AES },
      { tag: huks.HuksTag.HUKS_TAG_KEY_SIZE, value: huks.HuksKeySize.HUKS_AES_KEY_SIZE_256 },
      { tag: huks.HuksTag.HUKS_TAG_PURPOSE, value: huks.HuksKeyPurpose.HUKS_KEY_PURPOSE_ENCRYPT | huks.HuksKeyPurpose.HUKS_KEY_PURPOSE_DECRYPT }
    ];
  }
  private async crypt(input: Uint8Array, nonce: Uint8Array, encrypt: boolean): Promise<Uint8Array> {
    const properties: huks.HuksParam[] = [
      { tag: huks.HuksTag.HUKS_TAG_KEY_SIZE, value: huks.HuksKeySize.HUKS_AES_KEY_SIZE_256 },
      { tag: huks.HuksTag.HUKS_TAG_ALGORITHM, value: huks.HuksKeyAlg.HUKS_ALG_AES },
      { tag: huks.HuksTag.HUKS_TAG_PURPOSE, value: encrypt ? huks.HuksKeyPurpose.HUKS_KEY_PURPOSE_ENCRYPT : huks.HuksKeyPurpose.HUKS_KEY_PURPOSE_DECRYPT },
      { tag: huks.HuksTag.HUKS_TAG_PADDING, value: huks.HuksKeyPadding.HUKS_PADDING_NONE },
      { tag: huks.HuksTag.HUKS_TAG_BLOCK_MODE, value: huks.HuksCipherMode.HUKS_MODE_GCM },
      { tag: huks.HuksTag.HUKS_TAG_NONCE, value: nonce },
      { tag: huks.HuksTag.HUKS_TAG_ASSOCIATED_DATA, value: this.encoder.encodeInto(this.options.namespace) }
    ];
    let data = input;
    if (!encrypt) {
      properties.push({ tag: huks.HuksTag.HUKS_TAG_AE_TAG, value: input.slice(input.length - 16) });
      data = input.slice(0, input.length - 16);
    }
    const handle = await huks.initSession(this.options.keyAlias, { properties });
    let finished = false;
    try {
      const result = await huks.finishSession(handle.handle, { properties, inData: data });
      finished = true;
      if (!result.outData) throw new Error('HUKS returned no output');
      return result.outData;
    } finally {
      if (!finished) await huks.abortSession(handle.handle, { properties });
    }
  }
}
