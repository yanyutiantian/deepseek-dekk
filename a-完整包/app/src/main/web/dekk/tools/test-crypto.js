/*
 * V2 加密模块自检（Node 环境）：用 node:crypto 当参照物，逐项对拍。
 * 用法：node tools/test-crypto.js
 */
const fs = require('fs');
const path = require('path');
const nodeCrypto = require('crypto');

// 把浏览器里缺的东西补上，让 12_crypto.js 能在 Node 里跑
const D = {
  cryptoGetRandom: (arr) => nodeCrypto.randomFillSync(arr),
};
global.window = { crypto: { getRandomValues: (arr) => nodeCrypto.randomFillSync(arr) } };
window.__DEKK__ = D;

const source = fs.readFileSync(path.join(__dirname, '..', 'src', '12_crypto.js'), 'utf8');
eval(source);

const c = D.crypto;
let failed = 0;
function check(name, ok, extra) {
  console.log((ok ? '  ✅ ' : '  ❌ ') + name + (ok ? '' : '   ' + (extra || '')));
  if (!ok) failed++;
}

/* ---------------------------------------------------------------- SHA-256 */
{
  const cases = ['', 'abc', '你好，DeepSeek', 'a'.repeat(1000)];
  let ok = true;
  cases.forEach((text) => {
    const mine = Buffer.from(c.sha256(Buffer.from(text, 'utf8'))).toString('base64');
    const ref = nodeCrypto.createHash('sha256').update(text, 'utf8').digest('base64');
    if (mine !== ref) { ok = false; console.log('    sha256 不一致:', text.slice(0, 12)); }
  });
  check('SHA-256', ok);
}

/* ------------------------------------------------------------ HMAC-SHA256 */
{
  let ok = true;
  [['k', 'v'], ['secret', '中文内容'], ['x'.repeat(100), 'data']].forEach(([k, v]) => {
    const mine = Buffer.from(c.hmacSha256(Buffer.from(k), Buffer.from(v, 'utf8'))).toString('base64');
    const ref = nodeCrypto.createHmac('sha256', k).update(v, 'utf8').digest('base64');
    if (mine !== ref) { ok = false; console.log('    hmac 不一致:', k, v); }
  });
  check('HMAC-SHA256', ok);
}

/* --------------------------------------------------------------- AES-CBC */
{
  const key = nodeCrypto.randomBytes(32);
  const iv = nodeCrypto.randomBytes(16);
  const plain = 'hello 世界 🌊 ' + 'x'.repeat(200);
  const mineCipher = Buffer.from(c.aesCbcEncrypt(key, iv, Buffer.from(plain, 'utf8')));
  const cipher = nodeCrypto.createCipheriv('aes-256-cbc', key, iv);
  const refCipher = Buffer.concat([cipher.update(plain, 'utf8'), cipher.final()]);
  check('AES-256-CBC 加密与 node 一致', mineCipher.equals(refCipher));

  const decrypted = Buffer.from(c.aesCbcDecrypt(key, iv, refCipher)).toString('utf8');
  check('AES-256-CBC 能解开 node 的密文', decrypted === plain);
}

/* ------------------------------------------------------------------ 信封 */
{
  const payload = JSON.stringify({ page: '1', search: '测试', token: 'abc' });
  const sealed = c.seal(payload);
  const envelope = JSON.parse(Buffer.from(c.base64Decode(sealed.env)).toString('utf8'));
  check('信封字段齐全', envelope.v === 2 && !!envelope.ts && !!envelope.nonce
    && !!envelope.key && !!envelope.iv && !!envelope.data && !!envelope.mac);

  // 用 node 复算 mac / AES 解密，验证信封内容
  const master = Buffer.from(sealed.master);
  const encKey = nodeCrypto.createHash('sha256').update(Buffer.concat([master, Buffer.from('enc')])).digest();
  const macKey = nodeCrypto.createHash('sha256').update(Buffer.concat([master, Buffer.from('mac')])).digest();
  const iv = Buffer.from(envelope.iv, 'base64');
  const data = Buffer.from(envelope.data, 'base64');
  const mac = nodeCrypto.createHmac('sha256', macKey).update(Buffer.concat([iv, data])).digest('base64');
  check('信封 MAC 正确', mac === envelope.mac);
  const decipher = nodeCrypto.createDecipheriv('aes-256-cbc', encKey, iv);
  const plain = Buffer.concat([decipher.update(data), decipher.final()]).toString('utf8');
  check('信封正文可用 node 解开', plain === payload);

  // RSA：用服务器私钥验证能不能解开 master
  const privateKeyPath = process.argv[2];
  if (privateKeyPath && fs.existsSync(privateKeyPath)) {
    try {
      const priv = fs.readFileSync(privateKeyPath, 'utf8');
      const rsaMaster = nodeCrypto.privateDecrypt(
        { key: priv, padding: nodeCrypto.constants.RSA_PKCS1_PADDING },
        Buffer.from(envelope.key, 'base64'),
      );
      check('RSA 加密的 master 能用私钥解开', rsaMaster.equals(master));
    } catch (e) {
      check('RSA 加密的 master 能用私钥解开', false, e.message);
    }
  } else {
    console.log('  ⏭️  跳过 RSA 校验（没传私钥路径）');
  }

  // 响应解密：用 node 造一个「服务端响应」，看能不能解开
  const respPlain = JSON.stringify({ ok: true, items: [{ id: 1, name: '魔女之旅' }] });
  const respIv = nodeCrypto.randomBytes(16);
  const respCipher = nodeCrypto.createCipheriv('aes-256-cbc', encKey, respIv);
  const respData = Buffer.concat([respCipher.update(respPlain, 'utf8'), respCipher.final()]);
  const respMac = nodeCrypto.createHmac('sha256', macKey).update(Buffer.concat([respIv, respData])).digest('base64');
  const respBody = JSON.stringify({
    v: 2,
    iv: respIv.toString('base64'),
    data: respData.toString('base64'),
    mac: respMac,
  });
  check('能解开服务端响应信封', c.open(respBody, master) === respPlain);
  check('明文响应原样返回', c.open('{"ok":false,"message":"请登录"}', master) === '{"ok":false,"message":"请登录"}');
}

console.log(failed === 0 ? '\n全部通过 ✅' : `\n有 ${failed} 项不通过 ❌`);
process.exit(failed === 0 ? 0 : 1);
