import { sensor } from '@kit.SensorServiceKit';

/** 所属 ArkTS Context 串行启停；100ms 采样、2.7g 阈值、1200ms 冷却，只释放自己的 sensor 回调。 */
export class GycDebugShakeDetector {
  private started: boolean = false;
  private closed: boolean = false;
  private generation: number = 0;
  private lastTriggerMillis: number = 0;
  private handler: ((data: sensor.AccelerometerResponse) => void) | null = null;

  /** STARTED 为注册成功，onShake 在原生回调 Context 执行；重复 start 不替换回调。 */
  start(onShake: () => void): string {
    if (this.closed) return 'CLOSED';
    if (this.started) return 'STARTED';
    if (this.handler) {
      try { this.stop(); } catch (_) { return 'REGISTRATION_FAILED'; }
    }
    try { sensor.getSingleSensorSync(sensor.SensorId.ACCELEROMETER); }
    catch (_) { return 'SENSOR_UNAVAILABLE'; }
    const generation = ++this.generation;
    const handler = (data: sensor.AccelerometerResponse): void => {
      if (!this.started || generation !== this.generation) return;
      const force = Math.sqrt(data.x * data.x + data.y * data.y + data.z * data.z) / 9.80665;
      const time = data.timestamp / 1000000;
      if (!Number.isFinite(force) || force < 2.7 || time - this.lastTriggerMillis < 1200) return;
      this.lastTriggerMillis = time;
      onShake();
    };
    try {
      sensor.on(sensor.SensorId.ACCELEROMETER, handler, { interval: 100000000 });
      this.handler = handler;
      this.started = true;
      return 'STARTED';
    } catch (_) { return 'REGISTRATION_FAILED'; }
  }
  /** 先抑制迟事件再卸载回调；off 失败保留句柄供重试，不重置冷却。 */
  stop(): void {
    this.started = false;
    this.generation++;
    if (this.handler) {
      sensor.off(sensor.SensorId.ACCELEROMETER, this.handler);
      this.handler = null;
    }
  }
  /** 永久关闭；off 失败可再次 close 重试，之后 start 仍返回 CLOSED。 */
  close(): void { this.closed = true; this.stop(); }
}
