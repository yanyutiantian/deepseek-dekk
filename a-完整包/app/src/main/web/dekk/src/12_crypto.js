/*
 * DEKK API V2 协议加密（服务端 服务端 crypto.php，安卓端 ApiCrypto.java）。
 *
 * 信封：
 *   master  = 32 随机字节（本次请求的会话密钥）
 *   encKey  = SHA256(master || "enc")        macKey = SHA256(master || "mac")
 *   iv      = 16 随机字节
 *   data    = AES-256-CBC/PKCS7(encKey, iv, 明文JSON)
 *   mac     = base64(HMAC-SHA256(macKey, iv || data))
 *   env     = base64(JSON{v,ts,nonce,key,iv,data,mac})，key = base64(RSA-PKCS1v1.5(master))
 * 请求：POST {action=名字&ver=v2}，body = {"v":2,"env":env}
 * 响应：JSON{v,iv,data,mac}，用同一把 master 解开；没有这些字段就是明文（错误信息/灰度）。
 *
 * 为什么要纯 JS 手写：宿主发消息走 XHR，响应是同步读 responseText 的，
 * WebCrypto 只有异步接口，会赶不上读取时机，所以这里全部用同步实现（RSA 用 BigInt）。
 */
(function (D) {
  'use strict';
  if (!D) return;

  /* ================================================================ 工具 */

  function utf8Bytes(text) {
    if (typeof TextEncoder === 'function') return new TextEncoder().encode(text);
    var escaped = unescape(encodeURIComponent(text)); // eslint-disable-line
    var out = new Uint8Array(escaped.length);
    for (var i = 0; i < escaped.length; i++) out[i] = escaped.charCodeAt(i) & 0xFF;
    return out;
  }

  function bytesUtf8(bytes) {
    if (typeof TextDecoder === 'function') return new TextDecoder('utf-8').decode(bytes);
    var binary = '';
    for (var i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
    try { return decodeURIComponent(escape(binary)); } // eslint-disable-line
    catch (e) { return binary; }
  }

  var B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

  /** 反查表：解码时避免逐字符 indexOf（包体可能很大） */
  var B64_INDEX = (function () {
    var table = new Array(256);
    for (var i = 0; i < 256; i++) table[i] = -1;
    for (i = 0; i < B64.length; i++) table[B64.charCodeAt(i)] = i;
    return table;
  })();

  function base64Encode(bytes) {
    var out = '';
    for (var i = 0; i < bytes.length; i += 3) {
      var b0 = bytes[i];
      var b1 = i + 1 < bytes.length ? bytes[i + 1] : NaN;
      var b2 = i + 2 < bytes.length ? bytes[i + 2] : NaN;
      out += B64.charAt(b0 >> 2);
      out += B64.charAt(((b0 & 3) << 4) | (isNaN(b1) ? 0 : b1 >> 4));
      out += isNaN(b1) ? '=' : B64.charAt(((b1 & 15) << 2) | (isNaN(b2) ? 0 : b2 >> 6));
      out += isNaN(b2) ? '=' : B64.charAt(b2 & 63);
    }
    return out;
  }

  function base64Decode(text) {
    var clean = String(text == null ? '' : text).replace(/[^A-Za-z0-9+/=]/g, '');
    var length = clean.length;
    while (length > 0 && clean.charAt(length - 1) === '=') length--;
    var out = new Uint8Array(Math.floor((length * 6) / 8));
    var buffer = 0;
    var bits = 0;
    var index = 0;
    for (var i = 0; i < length; i++) {
      var value = B64_INDEX[clean.charCodeAt(i) & 0xFF];
      if (value < 0) continue;
      buffer = (buffer << 6) | value;
      bits += 6;
      if (bits >= 8) {
        bits -= 8;
        out[index++] = (buffer >> bits) & 0xFF;
        // 关键：把已取出的位丢掉，否则长字符串会超出 32 位精度、丢字节
        buffer &= (1 << bits) - 1;
      }
    }
    return index === out.length ? out : out.slice(0, index);
  }

  function concat(a, b) {
    var out = new Uint8Array(a.length + b.length);
    out.set(a, 0);
    out.set(b, a.length);
    return out;
  }

  function randomBytes(count) {
    var out = new Uint8Array(count);
    if (D.cryptoGetRandom) return D.cryptoGetRandom(out);
    if (window.crypto && window.crypto.getRandomValues) return window.crypto.getRandomValues(out);
    for (var i = 0; i < count; i++) out[i] = Math.floor(Math.random() * 256);
    return out;
  }

  function randomHex(byteCount) {
    var bytes = randomBytes(byteCount);
    var out = '';
    for (var i = 0; i < bytes.length; i++) {
      out += ('0' + bytes[i].toString(16)).slice(-2);
    }
    return out;
  }

  /* ============================================================= SHA-256 */

  var K256 = [
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
  ];

  function sha256(bytes) {
    var H = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19];
    var length = bytes.length;
    var withPadding = new Uint8Array((((length + 9) >> 6) + 1) << 6);
    withPadding.set(bytes, 0);
    withPadding[length] = 0x80;
    var bitLength = length * 8;
    // 末尾 8 字节写位长度（大端）；JS 数值够用（< 2^53）
    for (var i = 0; i < 8; i++) {
      withPadding[withPadding.length - 1 - i] = Math.floor(bitLength / Math.pow(2, 8 * i)) & 0xFF;
    }

    var W = new Array(64);
    for (var offset = 0; offset < withPadding.length; offset += 64) {
      for (var t = 0; t < 16; t++) {
        var j = offset + t * 4;
        W[t] = ((withPadding[j] << 24) | (withPadding[j + 1] << 16) | (withPadding[j + 2] << 8) | withPadding[j + 3]) >>> 0;
      }
      for (t = 16; t < 64; t++) {
        var w15 = W[t - 15];
        var w2 = W[t - 2];
        var s0 = (rotr(w15, 7) ^ rotr(w15, 18) ^ (w15 >>> 3)) >>> 0;
        var s1 = (rotr(w2, 17) ^ rotr(w2, 19) ^ (w2 >>> 10)) >>> 0;
        W[t] = (W[t - 16] + s0 + W[t - 7] + s1) >>> 0;
      }
      var a = H[0], b = H[1], c = H[2], d = H[3], e = H[4], f = H[5], g = H[6], h = H[7];
      for (t = 0; t < 64; t++) {
        var S1 = (rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)) >>> 0;
        var ch = ((e & f) ^ (~e & g)) >>> 0;
        var temp1 = (h + S1 + ch + K256[t] + W[t]) >>> 0;
        var S0 = (rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)) >>> 0;
        var maj = ((a & b) ^ (a & c) ^ (b & c)) >>> 0;
        var temp2 = (S0 + maj) >>> 0;
        h = g; g = f; f = e;
        e = (d + temp1) >>> 0;
        d = c; c = b; b = a;
        a = (temp1 + temp2) >>> 0;
      }
      H[0] = (H[0] + a) >>> 0; H[1] = (H[1] + b) >>> 0; H[2] = (H[2] + c) >>> 0; H[3] = (H[3] + d) >>> 0;
      H[4] = (H[4] + e) >>> 0; H[5] = (H[5] + f) >>> 0; H[6] = (H[6] + g) >>> 0; H[7] = (H[7] + h) >>> 0;
    }

    var out = new Uint8Array(32);
    for (var i2 = 0; i2 < 8; i2++) {
      out[i2 * 4] = (H[i2] >>> 24) & 0xFF;
      out[i2 * 4 + 1] = (H[i2] >>> 16) & 0xFF;
      out[i2 * 4 + 2] = (H[i2] >>> 8) & 0xFF;
      out[i2 * 4 + 3] = H[i2] & 0xFF;
    }
    return out;
  }

  function rotr(value, bits) {
    return ((value >>> bits) | (value << (32 - bits))) >>> 0;
  }

  function hmacSha256(keyBytes, data) {
    var blockSize = 64;
    var key = keyBytes.length > blockSize ? sha256(keyBytes) : keyBytes;
    var padded = new Uint8Array(blockSize);
    padded.set(key, 0);
    var oKey = new Uint8Array(blockSize);
    var iKey = new Uint8Array(blockSize);
    for (var i = 0; i < blockSize; i++) {
      oKey[i] = padded[i] ^ 0x5C;
      iKey[i] = padded[i] ^ 0x36;
    }
    return sha256(concat(oKey, sha256(concat(iKey, data))));
  }

  /* ========================================================== AES-CBC */

  var SBOX = null;
  var RCON = [0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1B, 0x36, 0x6C, 0xD8, 0xAB, 0x4D];

  function buildSbox() {
    var sbox = new Uint8Array(256);
    var p = 1;
    var q = 1;
    do {
      p = (p ^ ((p << 1) & 0xFF) ^ ((p & 0x80) ? 0x1B : 0)) & 0xFF;
      q ^= (q << 1) & 0xFF;
      q ^= (q << 2) & 0xFF;
      q ^= (q << 4) & 0xFF;
      q &= 0xFF;
      if (q & 0x80) q ^= 0x09;
      var x = q ^ ((q << 1) | (q >> 7)) ^ ((q << 2) | (q >> 6)) ^ ((q << 3) | (q >> 5)) ^ ((q << 4) | (q >> 4));
      sbox[p] = (x ^ 0x63) & 0xFF;
    } while (p !== 1);
    sbox[0] = 0x63;
    return sbox;
  }

  function gmul(a, b) {
    var p = 0;
    for (var i = 0; i < 8; i++) {
      if (b & 1) p ^= a;
      var hi = a & 0x80;
      a = (a << 1) & 0xFF;
      if (hi) a ^= 0x1B;
      b >>= 1;
    }
    return p & 0xFF;
  }

  function expandKey(key) {
    var nk = key.length / 4;
    var rounds = nk + 6;
    var total = 4 * (rounds + 1);
    var w = new Uint8Array(total * 4);
    w.set(key, 0);
    for (var i = nk; i < total; i++) {
      var off = i * 4;
      var temp = [w[(i - 1) * 4], w[(i - 1) * 4 + 1], w[(i - 1) * 4 + 2], w[(i - 1) * 4 + 3]];
      if (i % nk === 0) {
        temp = [SBOX[temp[1]] ^ RCON[i / nk - 1], SBOX[temp[2]], SBOX[temp[3]], SBOX[temp[0]]];
      } else if (nk > 6 && i % nk === 4) {
        temp = [SBOX[temp[0]], SBOX[temp[1]], SBOX[temp[2]], SBOX[temp[3]]];
      }
      for (var j = 0; j < 4; j++) w[off + j] = w[(i - nk) * 4 + j] ^ temp[j];
    }
    return { words: w, rounds: rounds };
  }

  function encryptBlock(key, input, out, offset) {
    var state = new Uint8Array(16);
    state.set(input);
    addRoundKey(key, state, 0);
    for (var round = 1; round < key.rounds; round++) {
      subBytes(state);
      shiftRows(state);
      mixColumns(state);
      addRoundKey(key, state, round);
    }
    subBytes(state);
    shiftRows(state);
    addRoundKey(key, state, key.rounds);
    out.set(state, offset);
  }

  function decryptBlock(key, input, out, offset) {
    var state = new Uint8Array(16);
    state.set(input);
    addRoundKey(key, state, key.rounds);
    for (var round = key.rounds - 1; round > 0; round--) {
      invShiftRows(state);
      invSubBytes(state);
      addRoundKey(key, state, round);
      invMixColumns(state);
    }
    invShiftRows(state);
    invSubBytes(state);
    addRoundKey(key, state, 0);
    out.set(state, offset);
  }

  function addRoundKey(key, state, round) {
    var w = key.words;
    for (var i = 0; i < 16; i++) state[i] ^= w[round * 16 + i];
  }

  function subBytes(state) {
    for (var i = 0; i < 16; i++) state[i] = SBOX[state[i]];
  }

  function invSubBytes(state) {
    for (var i = 0; i < 16; i++) state[i] = INV_SBOX[state[i]];
  }

  function shiftRows(state) {
    var t = new Uint8Array(16);
    t[0] = state[0]; t[4] = state[4]; t[8] = state[8]; t[12] = state[12];
    t[1] = state[5]; t[5] = state[9]; t[9] = state[13]; t[13] = state[1];
    t[2] = state[10]; t[6] = state[14]; t[10] = state[2]; t[14] = state[6];
    t[3] = state[15]; t[7] = state[3]; t[11] = state[7]; t[15] = state[11];
    state.set(t);
  }

  function invShiftRows(state) {
    var t = new Uint8Array(16);
    t[0] = state[0]; t[4] = state[4]; t[8] = state[8]; t[12] = state[12];
    t[5] = state[1]; t[9] = state[5]; t[13] = state[9]; t[1] = state[13];
    t[10] = state[2]; t[14] = state[6]; t[2] = state[10]; t[6] = state[14];
    t[15] = state[3]; t[3] = state[7]; t[7] = state[11]; t[11] = state[15];
    state.set(t);
  }

  function mixColumns(state) {
    for (var c = 0; c < 4; c++) {
      var i = c * 4;
      var a0 = state[i], a1 = state[i + 1], a2 = state[i + 2], a3 = state[i + 3];
      state[i] = gmul(a0, 2) ^ gmul(a1, 3) ^ a2 ^ a3;
      state[i + 1] = a0 ^ gmul(a1, 2) ^ gmul(a2, 3) ^ a3;
      state[i + 2] = a0 ^ a1 ^ gmul(a2, 2) ^ gmul(a3, 3);
      state[i + 3] = gmul(a0, 3) ^ a1 ^ a2 ^ gmul(a3, 2);
    }
  }

  function invMixColumns(state) {
    for (var c = 0; c < 4; c++) {
      var i = c * 4;
      var a0 = state[i], a1 = state[i + 1], a2 = state[i + 2], a3 = state[i + 3];
      state[i] = gmul(a0, 14) ^ gmul(a1, 11) ^ gmul(a2, 13) ^ gmul(a3, 9);
      state[i + 1] = gmul(a0, 9) ^ gmul(a1, 14) ^ gmul(a2, 11) ^ gmul(a3, 13);
      state[i + 2] = gmul(a0, 13) ^ gmul(a1, 9) ^ gmul(a2, 14) ^ gmul(a3, 11);
      state[i + 3] = gmul(a0, 11) ^ gmul(a1, 13) ^ gmul(a2, 9) ^ gmul(a3, 14);
    }
  }

  var INV_SBOX = null;

  function initTables() {
    if (SBOX) return;
    SBOX = buildSbox();
    INV_SBOX = new Uint8Array(256);
    for (var i = 0; i < 256; i++) INV_SBOX[SBOX[i]] = i;
  }

  function pkcs7Pad(data) {
    var pad = 16 - (data.length % 16);
    var out = new Uint8Array(data.length + pad);
    out.set(data, 0);
    for (var i = data.length; i < out.length; i++) out[i] = pad;
    return out;
  }

  function pkcs7Unpad(data) {
    if (!data.length || data.length % 16 !== 0) return data;
    var pad = data[data.length - 1];
    if (pad < 1 || pad > 16 || pad > data.length) return data;
    for (var i = data.length - pad; i < data.length; i++) {
      if (data[i] !== pad) return data;
    }
    return data.slice(0, data.length - pad);
  }

  function aesCbcEncrypt(keyBytes, iv, plain) {
    initTables();
    var key = expandKey(keyBytes);
    var data = pkcs7Pad(plain);
    var out = new Uint8Array(data.length);
    var previous = iv;
    for (var offset = 0; offset < data.length; offset += 16) {
      var block = new Uint8Array(16);
      for (var i = 0; i < 16; i++) block[i] = data[offset + i] ^ previous[i];
      encryptBlock(key, block, out, offset);
      previous = out.slice(offset, offset + 16);
    }
    return out;
  }

  function aesCbcDecrypt(keyBytes, iv, cipher) {
    initTables();
    var key = expandKey(keyBytes);
    if (cipher.length % 16 !== 0) throw new Error('密文长度不合法');
    var out = new Uint8Array(cipher.length);
    var previous = iv;
    for (var offset = 0; offset < cipher.length; offset += 16) {
      var block = cipher.slice(offset, offset + 16);
      var plain = new Uint8Array(16);
      decryptBlock(key, block, plain, 0);
      for (var i = 0; i < 16; i++) out[offset + i] = plain[i] ^ previous[i];
      previous = block;
    }
    return pkcs7Unpad(out);
  }

  /* ==================================================== RSA（PKCS1 v1.5） */

  var PUBLIC_KEY_BASE64 =
    'MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAxSdKjVUftbGZ3+vOQKPbDiaBwUUtEl7AwyhnpdZxEMbGDKsG9oNPkJDjtdVcB1PFB6Hj7R63GQFjJkGuPTcHrf4fcp3nRyKr1kz/s76LH6eRp+ldEXygX20B3JlTTB0aSM/NKLNa9Wzp2ml2INsjbtzzCllwS9boCd/p7vnRSTKM4owqbY1VMq6cigj+4Ceg1rtF53n/W1q3AiptemzbViz0vUh7KXHL1ijnNWOih0q4cZi1wJ4hle6PiQW76ofWjwXIMAX9JhHbdozayYeX5GlOKXvqTJ9zVcIhK/NSfXPWhyDqsxD+meKMWFYb8nDjfpU7uSbMH1xRxY2bX/yiBQIDAQAB';

  /** 极简 DER 解析：从 SPKI 里取模数 n 和指数 e */
  function parsePublicKey(base64) {
    var der = base64Decode(base64);
    var index = 0;
    function readLength() {
      var first = der[index++];
      if (first < 0x80) return first;
      var count = first & 0x7F;
      var value = 0;
      for (var i = 0; i < count; i++) value = (value << 8) | der[index++];
      return value;
    }
    function readTag() { return der[index++]; }
    // SEQUENCE
    readTag();
    readLength();
    // 内层 SEQUENCE（算法标识）
    readTag();
    var algLength = readLength();
    index += algLength;
    // BIT STRING
    readTag();
    readLength();
    index++; // 未使用位数
    // 内层 SEQUENCE：INTEGER n, INTEGER e
    readTag();
    readLength();
    function readInteger() {
      readTag();
      var length = readLength();
      var start = index;
      index += length;
      var value = 0n;
      for (var i = start; i < start + length; i++) value = (value << 8n) | BigInt(der[i]);
      return value;
    }
    var n = readInteger();
    var e = readInteger();
    return { n: n, e: e, size: (n.toString(2).length + 7) >> 3 };
  }

  var cachedKey = null;

  function modPow(base, exponent, modulus) {
    var result = 1n;
    var b = base % modulus;
    var exp = exponent;
    while (exp > 0n) {
      if (exp & 1n) result = (result * b) % modulus;
      b = (b * b) % modulus;
      exp >>= 1n;
    }
    return result;
  }

  function rsaEncrypt(bytes) {
    if (!cachedKey) cachedKey = parsePublicKey(PUBLIC_KEY_BASE64);
    var key = cachedKey;
    var k = key.size;
    if (bytes.length > k - 11) throw new Error('RSA 明文过长');
    var psLength = k - 3 - bytes.length;
    var em = new Uint8Array(k);
    em[0] = 0x00;
    em[1] = 0x02;
    var ps = randomBytes(psLength);
    for (var i = 0; i < psLength; i++) em[2 + i] = ps[i] === 0 ? 1 : ps[i];
    em[2 + psLength] = 0x00;
    em.set(bytes, 3 + psLength);
    var m = 0n;
    for (i = 0; i < k; i++) m = (m << 8n) | BigInt(em[i]);
    var c = modPow(m, key.e, key.n);
    var out = new Uint8Array(k);
    for (i = k - 1; i >= 0; i--) {
      out[i] = Number(c & 0xFFn);
      c >>= 8n;
    }
    return out;
  }

  /* ============================================================== 信封 */

  function derive(master, label) {
    return sha256(concat(master, utf8Bytes(label)));
  }

  /**
   * 把明文打成信封。
   * 返回 { env: base64(信封JSON), master: Uint8Array(32) } ——响应要用同一把 master 解。
   */
  function seal(plaintext) {
    initTables();
    var master = randomBytes(32);
    var encKey = derive(master, 'enc');
    var macKey = derive(master, 'mac');
    var iv = randomBytes(16);
    var cipher = aesCbcEncrypt(encKey, iv, utf8Bytes(plaintext));
    var mac = base64Encode(hmacSha256(macKey, concat(iv, cipher)));
    var envelope = JSON.stringify({
      v: 2,
      ts: Math.floor(Date.now() / 1000),
      nonce: randomHex(16),
      key: base64Encode(rsaEncrypt(master)),
      iv: base64Encode(iv),
      data: base64Encode(cipher),
      mac: mac,
    });
    return { env: base64Encode(utf8Bytes(envelope)), master: master };
  }

  /** 解开响应；不是加密信封（明文/错误信息）时原样返回 */
  function open(text, master) {
    if (typeof text !== 'string' || !text) return text;
    var trimmed = text.trim();
    if (!trimmed || trimmed.charAt(0) !== '{') return text;
    var outer = null;
    try { outer = JSON.parse(trimmed); } catch (e) { return text; }
    if (!outer || !outer.data || !outer.iv || !outer.mac) return text;
    if (!master) throw new Error('缺少会话密钥');
    var encKey = derive(master, 'enc');
    var macKey = derive(master, 'mac');
    var iv = base64Decode(outer.iv);
    var cipher = base64Decode(outer.data);
    var expect = base64Encode(hmacSha256(macKey, concat(iv, cipher)));
    if (expect !== String(outer.mac)) throw new Error('响应校验失败');
    return bytesUtf8(aesCbcDecrypt(encKey, iv, cipher));
  }

  D.crypto = {
    seal: seal,
    open: open,
    base64Encode: base64Encode,
    base64Decode: base64Decode,
    sha256: sha256,
    hmacSha256: hmacSha256,
    aesCbcEncrypt: aesCbcEncrypt,
    aesCbcDecrypt: aesCbcDecrypt,
    randomBytes: randomBytes,
    /** 仅供本地自检使用 */
    _internals: { expandKey: expandKey, parsePublicKey: parsePublicKey, initTables: initTables },
  };
})(window.__DEKK__);