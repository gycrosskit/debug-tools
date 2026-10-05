import { common } from '@kit.AbilityKit';
import { preferences } from '@kit.ArkData';
import { huks } from '@kit.UniversalKeystoreKit';
import { cryptoFramework } from '@kit.CryptoArchitectureKit';
import { util } from '@kit.ArkTS';

/** 所有字段为 1..128 个 ASCII 字母/数字/_.-，五个数据键须唯一；宿主保持升级隔离域一致。 */
export interface GycDebugStoreOptions {
  /** 私有 preferences 域，作为 AES-GCM AAD；不得跨品牌共用。 */
  namespace: string;
  /** HUKS AES-256 密钥别名，同 alias 的所有实例共享串行队列。 */
  keyAlias: string;
  /** 只保存密文 JSON 的键。 */
  tokenKey: string;
  /** 用户开关键，缺失读取 true。 */
  shakeEnabledKey: string;
  /** 历史 JSON 键，缺失读取 []。 */
  historyKey: string;
  /** 草稿 JSON 键，缺失读取 {}，损坏不回退为空。 */
  draftKey: string;
  /** 防重 journal JSON 键，缺失读取 []，损坏必须报错。 */
  pendingKey: string;
}
interface EncryptedToken { nonce: string; cipher: string; }

/**
 * 所属 ArkTS Context 调用，同 HUKS alias 的读写/清理使用共享串行队列；失败不阻塞后续任务。
 * Token 不落明文，普通记录写入等 flush；系统 I/O 不可取消，active 仅控制排队与提交资格。
 * 实例不拥有 UIAbilityContext 或传感器；宿主释放页面并撤回 active，不删除授权文件/其他记录。
 */
export class GycDebugStore {
  private static queues: Map<string, Promise<void>> = new Map();
  private readonly encoder = new util.TextEncoder();
  private readonly base64 = new util.Base64Helper();
  private readonly context: common.UIAbilityContext;
  private readonly options: GycDebugStoreOptions;
  /** 校验并持有宿主 Context/配置；不生成密钥，不执行存储或隐私准入。 */
  constructor(context: common.UIAbilityContext, options: GycDebugStoreOptions) {
    this.context = context; this.options = options;
    const keys = [options.namespace, options.keyAlias, options.tokenKey, options.shakeEnabledKey,
      options.historyKey, options.draftKey, options.pendingKey];
    keys.forEach((key: string) => {
      if (typeof key !== 'string' || !/^[A-Za-z0-9_.-]{1,128}$/.test(key)) throw new Error('Invalid debug store key');
    });
    if (new Set(keys.slice(2)).size !== 5) throw new Error('Debug store keys must be distinct');
  }

  /** 解密 Token；缺失为空，临时 HUKS/坏密文报错且保留原凭据，不降级明文；active 控制开始资格。 */
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

  /** token 为 1..16384 UTF-16 字符；随机 12 字节 nonce 加密，活动资格有效才 put/flush。 */
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

  /** 仅删密文 Token 并等 flush，保留密钥、历史、草稿、journal；active 失效不发起提交。 */
  clearCredentials(active: () => boolean = (): boolean => true): Promise<void> { return this.serial(async (): Promise<void> => {
    const prefs = await this.prefs();
    this.checkActive(active);
    await prefs.delete(this.options.tokenKey);
    await prefs.flush();
  }, active); }

  /** 读取已知普通记录方法的原始 JSON/布尔文本；类型错误报告，序列化由 Kotlin 接口解析。 */
  read(method: string, active: () => boolean = (): boolean => true): Promise<string> { return this.serial(async (): Promise<string> => {
    const key = this.key(method);
    const fallback = method === 'readShakeEnabled' ? 'true' : method === 'readDraft' ? '{}' : '[]';
    const result = await (await this.prefs()).get(key, fallback);
    if (typeof result !== 'string') throw new Error('Invalid debug record');
    return result;
  }, active); }

  /** 写已知普通记录，最多 1 Mi UTF-16 字符，先验证 JSON 再等 flush；取消不撤销已开始提交。 */
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
