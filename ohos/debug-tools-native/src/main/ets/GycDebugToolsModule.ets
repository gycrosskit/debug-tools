import { KRAny, KuiklyRenderBaseModule, KuiklyRenderCallback } from '@kuikly-open/render';
import { GycDebugStore, GycDebugStoreOptions } from './GycDebugStore';
import { GycDebugShakeDetector } from './GycDebugShakeDetector';

interface DebugParams extends GycDebugStoreOptions { requestId: string; value: string; }
interface DebugResult { status: string; value?: string; }

/** 每个渲染页面独占 Module；ArkTS Context 调用，requestId 拥有回调/存储提交资格，销毁后无回调。 */
export class GycDebugToolsModule extends KuiklyRenderBaseModule {
  /** 与 Kotlin Module.NAME 一致的注册名。 */
  static readonly MODULE_NAME: string = 'GycDebugToolsModule';
  private store: GycDebugStore | null = null;
  private readonly shake = new GycDebugShakeDetector();
  private destroyed: boolean = false;
  private readonly pending: Set<string> = new Set();
  private shakeRequestId: string | null = null;
  private configuration: string = '';
  /** 使用异步桥调用，不让原生 I/O 阻塞同步渲染返回值。 */
  syncMode(): boolean { return false; }

  /** 解析请求，拒绝重复 id；取消只撤回资格，系统 I/O 未必可撤销；回调不回显异常/凭据。 */
  call(method: string, params: KRAny, callback: KuiklyRenderCallback | null): KRAny {
    if (this.destroyed) return null;
    let args: DebugParams;
    try { args = JSON.parse(typeof params === 'string' ? params : '{}') as DebugParams; }
    catch (_) { callback?.({ status: 'error' }); return null; }
    if (!args || typeof args.requestId !== 'string' || args.requestId.length === 0 ||
      args.requestId.length > 128 || (method.startsWith('write') && typeof args.value !== 'string')) {
      callback?.({ status: 'error' }); return null;
    }
    if (method === 'cancel') {
      this.pending.delete(args.requestId);
      if (this.shakeRequestId === args.requestId) { this.shakeRequestId = null; this.shake.stop(); }
      return null;
    }
    if (!args.requestId || this.pending.has(args.requestId)) { callback?.({ status: 'error' }); return null; }
    this.pending.add(args.requestId);
    if (method === 'startShake') {
      if (this.shakeRequestId !== null) { this.pending.delete(args.requestId); callback?.({ status: 'error' }); return null; }
      const result = this.shake.start((): void => {
        if (!this.destroyed && this.pending.has(args.requestId)) callback?.({ status: 'shake' });
      });
      if (result === 'STARTED') this.shakeRequestId = args.requestId;
      callback?.({ status: 'ok', value: result });
      if (result !== 'STARTED') this.pending.delete(args.requestId);
      return null;
    }
    this.perform(method, args).then((result: DebugResult): void => {
      if (!this.destroyed && this.pending.delete(args.requestId)) callback?.(result);
    }).catch((): void => {
      if (!this.destroyed && this.pending.delete(args.requestId)) callback?.({ status: 'error' });
    });
    return null;
  }
  private async perform(method: string, args: DebugParams): Promise<DebugResult> {
    if (method === 'configure') {
      const options: GycDebugStoreOptions = { namespace: args.namespace, keyAlias: args.keyAlias,
        tokenKey: args.tokenKey, shakeEnabledKey: args.shakeEnabledKey, historyKey: args.historyKey,
        draftKey: args.draftKey, pendingKey: args.pendingKey };
      const fingerprint = JSON.stringify(options);
      if (this.store) {
        if (fingerprint !== this.configuration) throw new Error('Debug store already configured differently');
        return { status: 'ok' };
      }
      const context = this.controller?.getUIAbilityContext();
      if (!context) throw new Error('Debug context unavailable');
      this.store = new GycDebugStore(context, options);
      this.configuration = fingerprint;
      return { status: 'ok' };
    }
    if (method === 'stopShake') {
      if (this.shakeRequestId) this.pending.delete(this.shakeRequestId);
      this.shakeRequestId = null; this.shake.stop(); return { status: 'ok' };
    }
    if (!this.store) throw new Error('Debug store is not configured');
    const active = (): boolean => !this.destroyed && this.pending.has(args.requestId);
    if (method === 'readToken') return { status: 'ok', value: await this.store.readToken(active) };
    if (method === 'writeToken') { await this.store.writeToken(args.value, active); return { status: 'ok' }; }
    if (method === 'clearCredentials') { await this.store.clearCredentials(active); return { status: 'ok' }; }
    if (method.startsWith('read')) return { status: 'ok', value: await this.store.read(method, active) };
    await this.store.write(method, args.value, active);
    return { status: 'ok' };
  }
  /** 撤回所有待请求资格并关闭所属 sensor，finally 释放基类资源；不删除工作区。 */
  onDestroy(): void {
    this.destroyed = true;
    this.pending.clear();
    try { this.shake.close(); } finally { super.onDestroy(); }
  }
}
