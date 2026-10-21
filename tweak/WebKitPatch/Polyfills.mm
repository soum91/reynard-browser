// Polyfills for features that newer Safari versions ship and iOS 16.1 WebKit lacks.
// Each one is guarded, so a feature the engine already has is never overwritten.
#import "Polyfills.h"

NSString *WKPPolyfillSource(void) {
    return @R"JS(
(function () {
  if (window.__wkpatch) return;
  try { Object.defineProperty(window, '__wkpatch', { value: '0.1.0' }); } catch (e) { return; }
  function def(obj, name, fn) {
    if (!obj || name in obj) return;
    try { Object.defineProperty(obj, name, { value: fn, writable: true, configurable: true }); } catch (e) {}
  }

  def(Object, 'groupBy', function (items, cb) {
    var out = Object.create(null), i = 0;
    for (var v of items) { var k = cb(v, i++); (out[k] || (out[k] = [])).push(v); }
    return out;
  });
  def(Map, 'groupBy', function (items, cb) {
    var out = new Map(), i = 0;
    for (var v of items) { var k = cb(v, i++); if (!out.has(k)) out.set(k, []); out.get(k).push(v); }
    return out;
  });
  def(Promise, 'withResolvers', function () {
    var resolve, reject;
    var promise = new this(function (a, b) { resolve = a; reject = b; });
    return { promise: promise, resolve: resolve, reject: reject };
  });
  def(Array, 'fromAsync', async function (src, mapFn, thisArg) {
    var out = [], i = 0;
    if (src[Symbol.asyncIterator] || src[Symbol.iterator]) {
      for await (var v of src) out.push(mapFn ? await mapFn.call(thisArg, v, i++) : v);
    } else {
      for (var j = 0; j < src.length; j++) { var x = await src[j]; out.push(mapFn ? await mapFn.call(thisArg, x, j) : x); }
    }
    return out;
  });

  var AP = Array.prototype;
  def(AP, 'toSorted', function (cmp) { return this.slice().sort(cmp); });
  def(AP, 'toReversed', function () { return this.slice().reverse(); });
  def(AP, 'toSpliced', function () { var c = this.slice(); c.splice.apply(c, arguments); return c; });
  def(AP, 'with', function (index, value) {
    var c = this.slice(), i = Math.trunc(index) || 0;
    if (i < 0) i += c.length;
    if (i < 0 || i >= c.length) throw new RangeError('Invalid index : ' + index);
    c[i] = value; return c;
  });
  def(AP, 'findLast', function (cb, t) { for (var i = this.length - 1; i >= 0; i--) if (cb.call(t, this[i], i, this)) return this[i]; });
  def(AP, 'findLastIndex', function (cb, t) { for (var i = this.length - 1; i >= 0; i--) if (cb.call(t, this[i], i, this)) return i; return -1; });
  def(Object, 'hasOwn', function (o, k) { return Object.prototype.hasOwnProperty.call(o, k); });
  def(String.prototype, 'isWellFormed', function () {
    var s = String(this); for (var i = 0; i < s.length; i++) {
      var c = s.charCodeAt(i);
      if (c >= 0xD800 && c <= 0xDBFF) { var d = s.charCodeAt(i + 1); if (d >= 0xDC00 && d <= 0xDFFF) i++; else return false; }
      else if (c >= 0xDC00 && c <= 0xDFFF) return false;
    } return true;
  });
  def(String.prototype, 'toWellFormed', function () {
    var s = String(this), out = '';
    for (var i = 0; i < s.length; i++) {
      var c = s.charCodeAt(i);
      if (c >= 0xD800 && c <= 0xDBFF) { var d = s.charCodeAt(i + 1); if (d >= 0xDC00 && d <= 0xDFFF) { out += s[i] + s[i + 1]; i++; } else out += '�'; }
      else if (c >= 0xDC00 && c <= 0xDFFF) out += '�';
      else out += s[i];
    } return out;
  });

  var SP = Set.prototype;
  function has(o, v) { return o.has(v); }
  def(SP, 'union', function (o) { var r = new Set(this); for (var v of o.keys()) r.add(v); return r; });
  def(SP, 'intersection', function (o) { var r = new Set(); for (var v of this) if (has(o, v)) r.add(v); return r; });
  def(SP, 'difference', function (o) { var r = new Set(); for (var v of this) if (!has(o, v)) r.add(v); return r; });
  def(SP, 'symmetricDifference', function (o) {
    var r = new Set(this); for (var v of o.keys()) { if (this.has(v)) r.delete(v); else r.add(v); } return r;
  });
  def(SP, 'isSubsetOf', function (o) { for (var v of this) if (!has(o, v)) return false; return true; });
  def(SP, 'isSupersetOf', function (o) { for (var v of o.keys()) if (!this.has(v)) return false; return true; });
  def(SP, 'isDisjointFrom', function (o) { for (var v of this) if (has(o, v)) return false; return true; });

  if (typeof URL !== 'undefined') def(URL, 'canParse', function (u, b) { try { new URL(u, b); return true; } catch (e) { return false; } });
  if (typeof AbortSignal !== 'undefined') {
    def(AbortSignal, 'timeout', function (ms) { var c = new AbortController(); setTimeout(function () { c.abort(new DOMException('signal timed out', 'TimeoutError')); }, ms); return c.signal; });
    def(AbortSignal, 'any', function (signals) {
      var c = new AbortController();
      for (var s of signals) { if (s.aborted) { c.abort(s.reason); break; } s.addEventListener('abort', function () { c.abort(this.reason); }, { once: true }); }
      return c.signal;
    });
  }
  if (typeof Response !== 'undefined') def(Response, 'json', function (data, init) {
    init = init || {}; var h = new Headers(init.headers); if (!h.has('content-type')) h.set('content-type', 'application/json');
    return new Response(JSON.stringify(data), { status: init.status, statusText: init.statusText, headers: h });
  });
  if (typeof ReadableStream !== 'undefined') def(ReadableStream.prototype, Symbol.asyncIterator, async function* () {
    var reader = this.getReader();
    try { for (;;) { var r = await reader.read(); if (r.done) return; yield r.value; } } finally { reader.releaseLock(); }
  });

  if (!window.requestIdleCallback) {
    window.requestIdleCallback = function (cb) {
      var start = Date.now();
      return setTimeout(function () { cb({ didTimeout: false, timeRemaining: function () { return Math.max(0, 50 - (Date.now() - start)); } }); }, 1);
    };
    window.cancelIdleCallback = function (id) { clearTimeout(id); };
  }
  if (typeof Element !== 'undefined') def(Element.prototype, 'checkVisibility', function (o) {
    o = o || {};
    if (!this.isConnected) return false;
    for (var e = this; e && e.nodeType === 1; e = e.parentElement) {
      var s = getComputedStyle(e);
      if (s.display === 'none') return false;
      if (e === this && s.contentVisibility === 'hidden') return false;
      if ((o.checkVisibilityCSS || o.visibilityProperty) && s.visibility !== 'visible' && e === this) return false;
      if ((o.checkOpacity || o.opacityProperty) && s.opacity === '0') return false;
    }
    return true;
  });

  // Iterator helpers (Safari 18.4).
  if (typeof Iterator === 'undefined') {
    var IteratorProto = Object.getPrototypeOf(Object.getPrototypeOf([][Symbol.iterator]()));
    var Iter = function Iterator() {};
    Iter.prototype = IteratorProto;
    def(window, 'Iterator', Iter);
    def(Iter, 'from', function (o) {
      var it = o[Symbol.iterator] ? o[Symbol.iterator]() : o;
      return (function* () { yield* { [Symbol.iterator]: function () { return it; } }; })();
    });
    def(IteratorProto, 'map', function (f) { var self = this; return (function* () { var i = 0; for (var v of self) yield f(v, i++); })(); });
    def(IteratorProto, 'filter', function (f) { var self = this; return (function* () { var i = 0; for (var v of self) if (f(v, i++)) yield v; })(); });
    def(IteratorProto, 'take', function (n) { var self = this; return (function* () { if (n <= 0) return; var i = 0; for (var v of self) { yield v; if (++i >= n) return; } })(); });
    def(IteratorProto, 'drop', function (n) { var self = this; return (function* () { var i = 0; for (var v of self) if (i++ >= n) yield v; })(); });
    def(IteratorProto, 'flatMap', function (f) { var self = this; return (function* () { var i = 0; for (var v of self) yield* f(v, i++); })(); });
    def(IteratorProto, 'toArray', function () { return Array.from(this); });
    def(IteratorProto, 'forEach', function (f) { var i = 0; for (var v of this) f(v, i++); });
    def(IteratorProto, 'some', function (f) { var i = 0; for (var v of this) if (f(v, i++)) return true; return false; });
    def(IteratorProto, 'every', function (f) { var i = 0; for (var v of this) if (!f(v, i++)) return false; return true; });
    def(IteratorProto, 'find', function (f) { var i = 0; for (var v of this) if (f(v, i++)) return v; });
    def(IteratorProto, 'reduce', function (f, init) {
      var i = 0, acc = init, first = arguments.length < 2;
      for (var v of this) { if (first) { acc = v; first = false; i++; continue; } acc = f(acc, v, i++); }
      if (first) throw new TypeError('Reduce of empty iterator with no initial value');
      return acc;
    });
  }
})();
)JS";
}
