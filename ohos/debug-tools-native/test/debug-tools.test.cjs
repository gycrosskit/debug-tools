const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const crypto = require('node:crypto');
const ts = require(path.join(process.env.DEVECO_STUDIO_HOME || '/Applications/DevEco-Studio.app/Contents', 'tools/hvigor/hvigor/node_modules/typescript'));

function load(name, dependencies) {
  const source = fs.readFileSync(path.join(__dirname, '../src/main/ets', `${name}.ets`), 'utf8');
  const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 } }).outputText;
  const exports = {};
  vm.runInNewContext(compiled, { exports, require: name => { assert.ok(name in dependencies, name); return dependencies[name]; }, Uint8Array });
  return exports[name];
}

function storeFixture() {
  const values = new Map(), keys = new Map(), sessions = new Map();
  let nextHandle = 0, beforeFinish = async () => {}, flushes = 0;
  const tags = { HUKS_TAG_ALGORITHM: 1, HUKS_TAG_KEY_SIZE: 2, HUKS_TAG_PURPOSE: 3, HUKS_TAG_PADDING: 4,
    HUKS_TAG_BLOCK_MODE: 5, HUKS_TAG_NONCE: 6, HUKS_TAG_ASSOCIATED_DATA: 7, HUKS_TAG_AE_TAG: 8 };
  const get = (properties, tag) => properties.find(p => p.tag === tag)?.value;
  const huks = {
    HuksTag: tags, HuksKeyAlg: { HUKS_ALG_AES: 1 }, HuksKeySize: { HUKS_AES_KEY_SIZE_256: 256 },
    HuksKeyPurpose: { HUKS_KEY_PURPOSE_ENCRYPT: 1, HUKS_KEY_PURPOSE_DECRYPT: 2 },
    HuksKeyPadding: { HUKS_PADDING_NONE: 0 }, HuksCipherMode: { HUKS_MODE_GCM: 1 },
    isKeyItemExist: async alias => keys.has(alias),
    generateKeyItem: async (alias, options) => { assert.equal(get(options.properties, tags.HUKS_TAG_KEY_SIZE), 256); keys.set(alias, crypto.randomBytes(32)); },
    initSession: async (alias, options) => { const handle = ++nextHandle; sessions.set(handle, { alias, options }); return { handle }; },
    finishSession: async (handle, options) => {
      await beforeFinish();
      const session = sessions.get(handle), props = options.properties;
      const nonce = get(props, tags.HUKS_TAG_NONCE), aad = get(props, tags.HUKS_TAG_ASSOCIATED_DATA);
      assert.equal(nonce.length, 12);
      let outData;
      if (get(props, tags.HUKS_TAG_PURPOSE) === 1) {
        const cipher = crypto.createCipheriv('aes-256-gcm', keys.get(session.alias), nonce);
        cipher.setAAD(aad);
        outData = Buffer.concat([cipher.update(options.inData), cipher.final(), cipher.getAuthTag()]);
      } else {
        const cipher = crypto.createDecipheriv('aes-256-gcm', keys.get(session.alias), nonce);
        cipher.setAAD(aad); cipher.setAuthTag(get(props, tags.HUKS_TAG_AE_TAG));
        outData = Buffer.concat([cipher.update(options.inData), cipher.final()]);
      }
      sessions.delete(handle);
      return { outData: new Uint8Array(outData) };
    },
    abortSession: async handle => { sessions.delete(handle); }
  };
  const util = {
    TextEncoder: class { encodeInto(value) { return new Uint8Array(Buffer.from(value)); } },
    TextDecoder: class { decodeWithStream(value) { return new TextDecoder('utf-8', { fatal: true }).decode(value); } },
    Base64Helper: class {
      encodeToStringSync(value) { return Buffer.from(value).toString('base64'); }
      decodeSync(value) { return new Uint8Array(Buffer.from(value, 'base64')); }
    }
  };
  const prefs = { get: async (key, fallback) => values.get(key) ?? fallback, put: async (key, value) => values.set(key, value),
    delete: async key => values.delete(key), flush: async () => { flushes++; } };
  const Store = load('GycDebugStore', { '@kit.ArkData': { preferences: { getPreferences: async () => prefs } },
    '@kit.UniversalKeystoreKit': { huks }, '@kit.CryptoArchitectureKit': { cryptoFramework: { createRandom: () => ({ generateRandomSync: n => ({ data: new Uint8Array(crypto.randomBytes(n)) }) }) } },
    '@kit.ArkTS': { util } });
  const options = { namespace: 'test-debug', keyAlias: 'test-key', tokenKey: 'token', shakeEnabledKey: 'shake', historyKey: 'history', draftKey: 'draft', pendingKey: 'pending' };
  return { Store, options, store: new Store({}, options), values, sessions, get flushes() { return flushes; }, set beforeFinish(value) { beforeFinish = value; } };
}

test('Token uses AES GCM fresh nonces, authentication tag and flush; clearing preserves ordinary records', async () => {
  const f = storeFixture();
  await f.store.writeToken('测试-token');
  const first = f.values.get('token');
  assert.ok(!first.includes('测试-token'));
  assert.equal(await f.store.readToken(), '测试-token');
  await f.store.writeToken('测试-token');
  assert.notEqual(JSON.parse(first).nonce, JSON.parse(f.values.get('token')).nonce);
  await f.store.write('writeDraft', '{"title":"draft"}');
  await f.store.write('writePending', '[{"id":"pending"}]');
  await f.store.clearCredentials();
  assert.equal(await f.store.readToken(), '');
  assert.equal(await f.store.read('readDraft'), '{"title":"draft"}');
  assert.equal(await f.store.read('readPending'), '[{"id":"pending"}]');
  assert.equal(f.sessions.size, 0);
  assert.equal(f.flushes, 5);
});

test('cancelled queued and in-flight writes cannot start a new preferences commit', async () => {
  const f = storeFixture();
  let release, entered;
  const start = new Promise(resolve => { entered = resolve; });
  f.beforeFinish = async () => { entered(); await new Promise(resolve => { release = resolve; }); };
  let active = true;
  const first = f.store.writeToken('token', () => active);
  await start;
  const queued = f.store.write('writeDraft', '{"title":"late"}', () => active);
  active = false;
  release();
  await assert.rejects(first, /cancelled/);
  await assert.rejects(queued, /cancelled/);
  assert.equal(f.values.size, 0);
  assert.equal(f.flushes, 0);
  assert.equal(f.sessions.size, 0);
});

test('shake uses gravity units and cooldown, unregisters its own callback, and never resurrects closed detector', () => {
  const handlers = new Set();
  let failedOff = false;
  const sensor = { SensorId: { ACCELEROMETER: 1 }, getSingleSensorSync: () => ({}),
    on: (_, handler, options) => { assert.equal(options.interval, 100000000); handlers.add(handler); },
    off: (_, handler) => { if (failedOff) throw Error('off failed'); handlers.delete(handler); } };
  const Detector = load('GycDebugShakeDetector', { '@kit.SensorServiceKit': { sensor } });
  const detector = new Detector();
  let shakes = 0;
  assert.equal(detector.start(() => shakes++), 'STARTED');
  const first = [...handlers][0];
  first({ x: 30, y: 0, z: 0, timestamp: 1500000000 });
  first({ x: 30, y: 0, z: 0, timestamp: 1600000000 });
  assert.equal(shakes, 1);
  failedOff = true;
  assert.throws(() => detector.stop(), /off failed/);
  assert.equal(detector.start(() => shakes++), 'REGISTRATION_FAILED');
  assert.equal(handlers.size, 1);
  first({ x: 30, y: 0, z: 0, timestamp: 3000000000 });
  assert.equal(shakes, 1);
  failedOff = false;
  assert.equal(detector.start(() => shakes++), 'STARTED');
  assert.equal(handlers.size, 1);
  detector.close();
  assert.equal(handlers.size, 0);
  assert.equal(detector.start(() => shakes++), 'CLOSED');
});


test('native module destruction revokes pending store owner and shake delivery', async () => {
  const f = storeFixture();
  let release, entered;
  const start = new Promise(resolve => { entered = resolve; });
  f.beforeFinish = async () => { entered(); await new Promise(resolve => { release = resolve; }); };
  const handlers = new Set();
  const sensor = { SensorId: { ACCELEROMETER: 1 }, getSingleSensorSync: () => ({}),
    on: (_, handler) => handlers.add(handler), off: (_, handler) => handlers.delete(handler) };
  const Detector = load('GycDebugShakeDetector', { '@kit.SensorServiceKit': { sensor } });
  class Base { constructor() { this.controller = { getUIAbilityContext: () => ({}) }; } onDestroy() {} }
  const Native = load('GycDebugToolsModule', { '@kuikly-open/render': { KuiklyRenderBaseModule: Base },
    './GycDebugStore': { GycDebugStore: f.Store }, './GycDebugShakeDetector': { GycDebugShakeDetector: Detector } });
  const module = new Native(), replies = [];
  module.call('configure', JSON.stringify({ ...f.options, requestId: 'config' }), reply => replies.push(reply));
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(replies.length, 1);
  module.call('startShake', JSON.stringify({ requestId: 'shake' }), reply => replies.push(reply));
  assert.equal(replies[1].value, 'STARTED');
  const oldHandler = [...handlers][0];
  module.call('writeToken', JSON.stringify({ requestId: 'token', value: 'token' }), reply => replies.push(reply));
  await start;
  module.onDestroy();
  release();
  await new Promise(resolve => setImmediate(resolve));
  oldHandler({ x: 30, y: 0, z: 0, timestamp: 3000000000 });
  assert.equal(replies.length, 2);
  assert.equal(handlers.size, 0);
  assert.equal(f.values.size, 0);
});


test('HUKS failure aborts its session, preserves existing ciphertext and does not poison the alias queue', async () => {
  const f = storeFixture();
  await f.store.writeToken('original-token');
  const original = f.values.get('token'), flushes = f.flushes;
  f.beforeFinish = async () => { throw Error('temporary HUKS failure'); };
  await assert.rejects(f.store.writeToken('replacement'), /HUKS failure/);
  assert.equal(f.sessions.size, 0);
  assert.equal(f.values.get('token'), original);
  assert.equal(f.flushes, flushes);
  await assert.rejects(f.store.readToken(), /HUKS failure/);
  assert.equal(f.sessions.size, 0);
  assert.equal(f.values.get('token'), original);
  f.beforeFinish = async () => {};
  assert.equal(await f.store.readToken(), 'original-token');
  await f.store.writeToken('recovered-token');
  assert.equal(await f.store.readToken(), 'recovered-token');
});

test('another store with the same alias cannot clear credentials while encryption is pending', async () => {
  const f = storeFixture();
  const second = new f.Store({}, f.options);
  let release, entered;
  const start = new Promise(resolve => { entered = resolve; });
  f.beforeFinish = async () => { entered(); await new Promise(resolve => { release = resolve; }); };
  const writing = f.store.writeToken('pending-token');
  await start;
  let cleared = false;
  const clearing = second.clearCredentials().then(() => { cleared = true; });
  await Promise.resolve();
  assert.equal(cleared, false);
  assert.equal(f.flushes, 0);
  release();
  await writing;
  await clearing;
  assert.equal(f.values.has('token'), false);
  assert.equal(f.flushes, 2);
  assert.equal(f.sessions.size, 0);
});

test('native bridge rejects null or mistyped arguments without starting native work', () => {
  let starts = 0;
  class Base { onDestroy() {} }
  class Store { constructor() { starts++; } }
  class Shake { start() { starts++; } }
  const Native = load('GycDebugToolsModule', { '@kuikly-open/render': { KuiklyRenderBaseModule: Base },
    './GycDebugStore': { GycDebugStore: Store }, './GycDebugShakeDetector': { GycDebugShakeDetector: Shake } });
  const module = new Native();
  for (const args of ['null', '[]', 'true', '1', '"text"', '{}', '{"requestId":1}', '{"requestId":""}',
    JSON.stringify({ requestId: 'x'.repeat(129) }), '{"requestId":"write","value":false}']) {
    const replies = [];
    assert.doesNotThrow(() => module.call('writeToken', args, reply => replies.push(reply)));
    assert.equal(replies.length, 1);
    assert.equal(replies[0].status, 'error');
  }
  assert.equal(starts, 0);
  const f = storeFixture();
  assert.throws(() => new f.Store({}, { ...f.options, pendingKey: undefined }), /Invalid debug store key/);
});
