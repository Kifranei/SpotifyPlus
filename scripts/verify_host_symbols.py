#!/usr/bin/env python3
"""
Offline check of every obfuscated Spotify name SpotifyPlus uses.

  python3 scripts/verify_host_symbols.py                       # default dex locations below
  python3 scripts/verify_host_symbols.py 9.1.98.521=/path/base.apk 9.1.90.2270=/path/dexdir

For every version table in app/src/main/java/.../hook/HostSymbols.kt that has dex input:
  1. resolves each mapped class / method and checks the *structure* the hooks re-check at runtime;
  2. re-runs the DexKit discovery fingerprints of DexResolver.discoverShare (unknown-version path) in Python and
     checks they land on the same names as the table;
  3. checks the unobfuscated anchors (ContextTrack, string anchors, explicit-badge resources when aapt2 is around).
Exit code 0 only if everything passes. Dex sources: a directory of classes*.dex, an .apk, or an .apkm/.xapk bundle.
"""
import io, os, pickle, re, struct, subprocess, sys, zipfile, hashlib, shutil, glob

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SYMBOLS_KT = os.path.join(ROOT, "app/src/main/java/dev/kifranei/spotifyplus/hook/HostSymbols.kt")
DEFAULT_INPUTS = {
    "9.1.90.2270": ["/workspace/apk/sp-x", "/home/box/Downloads/spotify-9.1.90.2270.apk"],
    "9.1.98.521": ["/workspace/apk/sp198/x",
                   "/home/box/Downloads/com.spotify.music_9.1.98.521-148115007_1arch_2dpi_1lang_2eeb5e766b3d3a9bc398ec4fc59fc362_apkmirror.com.apkm"],
}
CACHE = os.path.expanduser("~/.cache/spotifyplus-dexidx")

# ---------------------------------------------------------------- dex parsing (minimal, no deps)
W = {}
def _r(a, b, w):
    for i in range(a, b + 1): W[i] = w
_r(0, 0xff, 1)
for k, v in {2: 2, 3: 3, 5: 2, 6: 3, 8: 2, 9: 3, 0x13: 2, 0x14: 3, 0x15: 2, 0x16: 2, 0x17: 3, 0x18: 5, 0x19: 2, 0x1a: 2, 0x1b: 3,
             0x1c: 2, 0x1f: 2, 0x20: 2, 0x22: 2, 0x23: 2, 0x24: 3, 0x25: 3, 0x26: 3, 0x29: 2, 0x2a: 3, 0x2b: 3, 0x2c: 3,
             0xfa: 4, 0xfb: 4, 0xfc: 3, 0xfd: 3, 0xfe: 2, 0xff: 2}.items():
    W[k] = v
_r(0x2d, 0x3d, 2); _r(0x44, 0x6d, 2); _r(0x6e, 0x72, 3); _r(0x74, 0x78, 3); _r(0x90, 0xaf, 2); _r(0xd0, 0xe2, 2)

def uleb(d, o):
    v = s = 0
    while True:
        b = d[o]; o += 1; v |= (b & 127) << s; s += 7
        if not b & 128: return v, o

def jname(desc):
    """Dex type descriptor -> Java name (as Class.getName / DexKit names)."""
    dim = 0
    while desc.startswith("["): dim += 1; desc = desc[1:]
    prim = {"V": "void", "Z": "boolean", "B": "byte", "S": "short", "C": "char", "I": "int", "J": "long", "F": "float", "D": "double"}
    n = prim.get(desc) or desc[1:-1].replace("/", ".")
    return n + "[]" * dim

def parse(data, out):
    u32 = lambda o: struct.unpack_from("<I", data, o)[0]; u16 = lambda o: struct.unpack_from("<H", data, o)[0]
    S = []
    for i in range(u32(56)):
        _, o = uleb(data, u32(u32(60) + 4 * i)); S.append(data[o:data.index(0, o)].decode("utf8", "replace"))
    T = [S[u32(u32(68) + 4 * i)] for i in range(u32(64))]
    P = []
    for i in range(u32(72)):
        b = u32(76) + i * 12; po = u32(b + 8)
        ps = [] if not po else [T[u16(po + 4 + j * 2)] for j in range(u32(po))]
        P.append((tuple(ps), T[u32(b + 4)]))
    F = []; M = []
    for i in range(u32(80)):
        c, t, n = struct.unpack_from("<HHI", data, u32(84) + i * 8); F.append((T[c], S[n], T[t]))
    for i in range(u32(88)):
        c, p, n = struct.unpack_from("<HHI", data, u32(92) + i * 8); M.append((T[c], S[n], P[p]))
    for i in range(u32(96)):
        b = u32(100) + i * 32; cn = T[u32(b)]; sup = u32(b + 8); io_ = u32(b + 12); off = u32(b + 24)
        itf = [T[u16(io_ + 4 + j * 2)] for j in range(u32(io_))] if io_ else []
        C = {"super": T[sup] if sup != 0xFFFFFFFF else None, "itf": itf, "flags": u32(b + 4), "fields": [], "methods": []}
        if off:
            cnt = []; o = off
            for _ in range(4):
                c, o = uleb(data, o); cnt.append(c)
            for k, sec in enumerate(cnt[:2]):
                idx = 0
                for _ in range(sec):
                    dl, o = uleb(data, o); idx += dl; fl, o = uleb(data, o)
                    C["fields"].append((F[idx][1], F[idx][2], fl))
            for sec in cnt[2:]:
                idx = 0
                for _ in range(sec):
                    dl, o = uleb(data, o); idx += dl; fl, o = uleb(data, o); co, o = uleb(data, o)
                    strs = []; inv = []; fr = []
                    if co:
                        ins = u32(co + 12); p = co + 16; e = p + ins * 2
                        while p < e:
                            w = u16(p); op = w & 0xff
                            if op == 0 and w >> 8:
                                k = w >> 8
                                if k == 1: L = u16(p + 2) * 2 + 4
                                elif k == 2: L = u16(p + 2) * 4 + 2
                                elif k == 3: L = (u32(p + 4) * u16(p + 2) + 1) // 2 + 4
                                else: L = 1
                                p += L * 2; continue
                            if op == 0x1a: strs.append(S[u16(p + 2)])
                            elif op == 0x1b: strs.append(S[u32(p + 2)])
                            elif 0x6e <= op <= 0x72 or 0x74 <= op <= 0x78: inv.append(M[u16(p + 2)])
                            elif 0x52 <= op <= 0x6d: fr.append(F[u16(p + 2)])
                            p += W[op] * 2
                    params, ret = M[idx][2]
                    C["methods"].append({"name": M[idx][1], "params": params, "ret": ret, "flags": fl, "strs": strs, "inv": inv, "fr": fr})
        out[cn] = C

def dex_blobs(src):
    if os.path.isdir(src):
        for f in sorted(glob.glob(os.path.join(src, "classes*.dex"))):
            yield open(f, "rb").read()
        return
    z = zipfile.ZipFile(src)
    names = z.namelist()
    if "base.apk" in names:  # apkm / xapk bundle
        z = zipfile.ZipFile(io.BytesIO(z.read("base.apk"))); names = z.namelist()
    for n in names:
        if re.fullmatch(r"classes\d*\.dex", n): yield z.read(n)

def load_index(src):
    st = os.stat(src)
    key = hashlib.sha1(f"v3|{os.path.abspath(src)}|{st.st_mtime}|{st.st_size}".encode()).hexdigest()
    os.makedirs(CACHE, exist_ok=True)
    cp = os.path.join(CACHE, key + ".pkl")
    if os.path.exists(cp):
        return pickle.load(open(cp, "rb"))
    out = {}
    for blob in dex_blobs(src): parse(blob, out)
    if not out: raise SystemExit(f"no dex found in {src}")
    pickle.dump(out, open(cp, "wb"))
    return out

# ---------------------------------------------------------------- HostSymbols.kt
def read_tables():
    src = open(SYMBOLS_KT, encoding="utf8").read()
    tables = {}
    for m in re.finditer(r'version\(\s*"([^"]+)"\s*,\s*mapOf\((.*?)\)\s*,?\s*\)', src, re.S):
        tables[m.group(1)] = dict(re.findall(r'"([^"]+)"\s+to\s+"([^"]+)"', m.group(2)))
    return tables

# ---------------------------------------------------------------- checks
ACC_STATIC, ACC_SYNTH = 0x8, 0x1000
LIST, SET, OBJ, STR = "Ljava/util/List;", "Ljava/util/Set;", "Ljava/lang/Object;", "Ljava/lang/String;"
D = lambda n: "L" + n.replace(".", "/") + ";"

class Ctx:
    def __init__(self, ver, N):
        self.ver, self.N, self.bad, self.ok = ver, N, 0, 0
    def check(self, cond, what):
        if cond: self.ok += 1; print(f"  ok   {what}")
        else: self.bad += 1; print(f"  FAIL {what}")
        return cond
    def cls(self, name): return self.N.get(D(name)) if name else None
    def methods(self, c, name=None): return [m for m in (c or {}).get("methods", []) if name is None or m["name"] == name]
    def ifields(self, c): return [f for f in (c or {}).get("fields", []) if not f[2] & ACC_STATIC]
    def field(self, c, n): return next((f for f in (c or {}).get("fields", []) if f[0] == n), None)
    def supers(self, d):
        while d in self.N: yield d; d = self.N[d]["super"]
    def a_owner(self, dest_desc, idt):
        """Topmost class in dest's super chain declaring a(): id — the class R8 uses in invoke-virtual a()."""
        own = None
        for d in self.supers(dest_desc):
            if any(m["name"] == "a" and not m["params"] and m["ret"] == idt for m in self.N[d]["methods"]): own = d
        return own
    def iface_of(self, d):
        c = self.N.get(d); return bool(c and c["flags"] & 0x200)
    def users_of_string(self, s, exact=True):
        for cn, c in self.N.items():
            for m in c["methods"]:
                if any((x == s) if exact else (s in x) for x in m["strs"]): yield cn, m

def verify_table(x, t):
    N = x.N
    print(f"\n== {x.ver}: structural check of {len(t)} mapped names")
    # ---- menuVm (MoreMenuHook)
    mv = x.cls(t.get("menuVm"))
    ctor = [m for m in x.methods(mv, "<init>") if not m["flags"] & ACC_SYNTH and len(m["params"]) == 3 and m["params"][1] == LIST and m["params"][2] == "Z"]
    x.check(bool(ctor), f"menuVm {t.get('menuVm')} has ctor (X, List, boolean)")
    x.check(any("ContextMenuViewModel cannot contain items with duplicate itemResId" in s for m in ctor for s in m["strs"]), "menuVm ctor uses the ContextMenuViewModel anchor")
    item = [cn for cn, c in N.items() for m in c["methods"] if m["name"] == "<init>" and "Exactly one title property must be populated." in m["strs"]]
    ok_item = False
    for cn in set(item):
        f = x.ifields(N[cn])
        if len(f) == 9 and f[0][1] == STR and f[3][1] == "Ljava/lang/Integer;" and f[4][1] == STR and f[6][1] == "Z":
            act = N.get(f[8][1]); af = x.ifields(act)
            ok_item = bool(af) and any(x.iface_of(ff[1]) for ff in af) and any(
                m["name"] == "<init>" and not m["flags"] & ACC_SYNTH and list(m["params"]) == [ff[1] for ff in af] for m in act["methods"])
            print(f"       menu item {jname(cn)} action {jname(f[8][1])} fields={[jname(ff[1]) for ff in af]}")
    x.check(ok_item, "menu item class: 9 fields (String,?,?,Integer,String,Integer,boolean,?,action) and action has interface callback + full ctor")
    # ---- headers (HeaderCapture)
    h = x.cls(t.get("headers"))
    x.check(any(m["params"] == ("[Ljava/lang/String;",) for m in x.methods(h, "<init>")), f"headers {t.get('headers')} has ctor (String[])")
    x.check(any(m["ret"] == D(t.get("headers", "")) for _, m in x.users_of_string("Expected alternating header names and values", exact=False)),
            "headers anchor method returns the headers class")
    # ---- share sheet (ShareMenuHook)
    dn = t.get("share.dest"); dest = x.cls(dn)
    if not x.check(dest is not None, f"share.dest {dn} exists"): return
    a = x.methods(dest, "a"); idt = a[0]["ret"] if a and not a[0]["params"] else None
    base = N.get(dest["super"], {}).get("super")
    x.check(idt is not None and any(m["name"] == "getValue" and m["ret"] == STR for m in N.get(idt, {}).get("methods", [])), f"dest.a() -> id type {idt and jname(idt)} with getValue():String")
    x.check(any(m["name"] == "c" and m["ret"] == "I" and not m["params"] for m in dest["methods"]) and any(m["name"] == "e" and m["ret"] == "I" and not m["params"] for m in dest["methods"]), "dest.c()/e() : int (a11y / label res)")
    fm = [m for m in x.methods(dest, "f") if len(m["params"]) == 2 and m["ret"] == OBJ]
    x.check(bool(fm), "dest.f(shareData, Continuation) : Object")
    x.check(any("Unsupported share data type for download: " in s for m in dest["methods"] for s in m["strs"]), "dest uses download anchor string")
    names = [f[0] for f in x.ifields(dest)]
    x.check(all(k in names for k in "abcdef"), f"dest instance fields a..f present ({names})")
    x.check(any(not m["flags"] & ACC_SYNTH and len(m["params"]) == 3 for m in x.methods(dest, "<init>")), "dest has non-synthetic 3-arg ctor")
    if fm:
        sd = N.get(fm[0]["params"][0]); fa = x.field(sd, "a")
        x.check(fa is not None and not fa[2] & ACC_STATIC, f"share-data holder {jname(fm[0]['params'][0])} has field a")
    x.check(base is not None, f"dest base (super.super) = {base and jname(base)}")
    aown = x.a_owner(D(dn), idt)
    x.check(aown is not None, f"a() owner (invoke target for destination.a()) = {aown and jname(aown)}")
    # ids
    for key, val in (("share.id.save", "qr-code"), ("share.id.share", "spotify-on-platform-users-nearby")):
        c = x.cls(t.get(key))
        x.check(c is not None and c["super"] == idt and x.field(c, "d") is not None and x.field(c, "d")[1] == D(t[key])
                and any(m["name"] == "<clinit>" and val in m["strs"] for m in c["methods"]), f"{key} {t.get(key)}: id subclass with static d, value \"{val}\"")
    # resolver
    rc = x.cls(t.get("share.resolver")); rm = [m for m in x.methods(rc, t.get("share.resolver.m")) if m["params"] == (idt,) and m["ret"] == base]
    x.check(bool(rm) and any("Destination with id " in s for s in rm[0]["strs"]), f"share.resolver {t.get('share.resolver')}.{t.get('share.resolver.m')}(id) -> dest base, uses 'Destination with id '")
    x.check(x.field(rc, "c") is not None, "resolver has field c (lazy id->destination map)")
    # config
    cc = x.cls(t.get("share.config")); cfg = D(t.get("share.config", ""))
    cti = [m["params"] for m in x.methods(cc, "<init>") if len(m["params"]) == 2 and sorted(m["params"]) == sorted(("I", LIST)) and not m["flags"] & ACC_SYNTH]
    x.check(bool(cti), f"share.config {t.get('share.config')} ctor {cti and cti[0]} (int+List, any order)")
    x.check(any(f[1] == LIST for f in x.ifields(cc)), "config has a List field")
    x.check(cc is not None and all(any(s in m["strs"] for m in cc["methods"]) for s in ("PLATFORM", "KEEP", "SMART")), "config writes PLATFORM/KEEP/SMART")
    # unit
    u = x.cls(t.get("share.unit"))
    x.check(u is not None and x.field(u, "a") is not None and x.field(u, "a")[2] & ACC_STATIC and any("kotlin.Unit" in m["strs"] for m in u["methods"]), f"share.unit {t.get('share.unit')} is kotlin.Unit (static a)")
    # set factory
    sf = x.cls(t.get("share.setFactory")); fd = x.field(dest, "d")
    sm = [m for m in x.methods(sf, t.get("share.setFactory.m")) if m["flags"] & ACC_STATIC and m["params"] == ("[Ljava/lang/Object;",)]
    x.check(bool(sm) and fd is not None and sm[0]["ret"] == fd[1], f"share.setFactory {t.get('share.setFactory')}.{t.get('share.setFactory.m')}(Object[]) returns dest.d type {fd and jname(fd[1])}")
    x.check(any((D(t.get("share.setFactory", "")), t.get("share.setFactory.m")) == (i[0], i[1]) for m in x.methods(dest, "<init>") for i in m["inv"]), "dest.<init> calls the set factory")
    # more
    mo = x.cls(t.get("share.more")); fi = x.field(dest, "f"); md = x.field(mo, "d")
    x.check(mo is not None and mo["super"] == dest["super"] and md is not None and fi is not None and md[1] == fi[1], f"share.more {t.get('share.more')}: sibling destination, field d has icon type {fi and jname(fi[1])}")
    x.check(mo is not None and any(any(f[0] != D(t.get("share.more", "")) and any("native-share-menu" in s for mm in N.get(f[0], {}).get("methods", []) for s in mm["strs"])
                                       for f in m["fr"]) for m in x.methods(mo, "a")), "more.a() returns the native-share-menu id")
    # lazy map
    for ln in t.get("share.lazyMap", "").split(","):
        lc = x.cls(ln); inv = [m for m in x.methods(lc, "invoke") if not m["params"] and m["ret"] == OBJ]
        x.check(bool(inv) and x.field(lc, "b") is not None and any(i[0] == "Ljava/util/LinkedHashMap;" and i[1] == "<init>" for i in inv[0]["inv"])
                and any(i[0] == aown and i[1] == "a" for i in inv[0]["inv"]),
                f"share.lazyMap {ln}: invoke() builds LinkedHashMap keyed by destination.a(), has field b")
    # hidden set
    for hn in t.get("share.hiddenSet", "").split(","):
        hc = x.cls(hn)
        ct = [m for m in x.methods(hc, "<init>") if not m["flags"] & ACC_SYNTH and list(m["params"]).count(SET) == 1]
        x.check(bool(ct) and "Landroid/os/Parcelable;" in hc["itf"], f"share.hiddenSet {hn}: Parcelable, ctor {ct and ct[0]['params']} with one Set")
    # final list
    fc = x.cls(t.get("share.finalList"))
    x.check(any(len(m["params"]) == 3 and m["params"][0] == cfg and m["params"][1] == LIST for m in x.methods(fc, t.get("share.finalList.m"))), f"share.finalList {t.get('share.finalList')}.{t.get('share.finalList.m')}(config, List, cont)")
    # filter
    lc = x.cls(t.get("share.filter"))
    x.check(any(m["flags"] & ACC_STATIC and m["params"] == (LIST, LIST) and m["ret"] == "Ljava/util/ArrayList;" and any(i[0] == aown and i[1] == "a" for i in m["inv"])
                for m in x.methods(lc, t.get("share.filter.m"))), f"share.filter {t.get('share.filter')}.{t.get('share.filter.m')}(List, List) static, calls destination.a()")

def discover(x):
    """Python mirror of DexResolver.discoverShare + the menuVm/headers DexKit queries (unknown-version path)."""
    N = x.N; out = {}
    out["menuVm"] = next((jname(cn) for cn, m in x.users_of_string("ContextMenuViewModel cannot contain items with duplicate itemResId", exact=False) if m["name"] == "<init>"), None)
    out["headers"] = next((jname(m["ret"]) for cn, m in x.users_of_string("Expected alternating header names and values", exact=False) if m["name"] not in ("<init>", "<clinit>")), None)
    dests = sorted({cn for cn, m in x.users_of_string("Unsupported share data type for download: ", exact=False) if any(mm["name"] == "a" for mm in N[cn]["methods"])})
    if not dests: return out
    dd = dests[0]; dest = N[dd]; out["share.dest"] = jname(dd)
    idt = next(m["ret"] for m in dest["methods"] if m["name"] == "a"); base = N[dest["super"]]["super"]; aown = x.a_owner(dd, idt)
    for key, val in (("share.id.save", "qr-code"), ("share.id.share", "spotify-on-platform-users-nearby")):
        hit = [cn for cn, m in x.users_of_string(val) if m["name"] == "<clinit>" and N[cn]["super"] == idt]
        if hit: out[key] = jname(hit[0])
    if "share.id.share" in out:
        sid = D(out["share.id.share"])
        op = [(cn, m) for cn, c in N.items() for m in c["methods"] if m["flags"] & ACC_STATIC and m["params"] == (idt,) and m["ret"] == "Z"
              and any(f[0] == sid and f[2] == sid for f in m["fr"])]
        if op: out["share.onPlatform"] = jname(op[0][0]); out["share.onPlatform.m"] = op[0][1]["name"]
    rm = [(cn, m) for cn, m in x.users_of_string("Destination with id ", exact=False) if m["params"] == (idt,) and m["ret"] == base]
    if rm: out["share.resolver"] = jname(rm[0][0]); out["share.resolver.m"] = rm[0][1]["name"]
    cfg = sorted({cn for cn, m in x.users_of_string("PLATFORM", exact=False) if "KEEP" in m["strs"] and "SMART" in m["strs"]
                  and any(len(k["params"]) == 2 and sorted(k["params"]) == sorted(("I", LIST)) for k in N[cn]["methods"] if k["name"] == "<init>")})
    if cfg: out["share.config"] = jname(cfg[0])
    un = sorted({cn for cn, m in x.users_of_string("kotlin.Unit") if x.field(N[cn], "a") is not None and x.field(N[cn], "a")[1] == cn})
    if un: out["share.unit"] = jname(un[0])
    for m in dest["methods"]:
        if m["name"] == "<init>":
            for i in m["inv"]:
                tc = N.get(i[0]); tm = next((mm for mm in (tc or {}).get("methods", []) if mm["name"] == i[1] and mm["params"] == i[2][0]), None)
                if i[2][0] == ("[Ljava/lang/Object;",) and tm and tm["flags"] & ACC_STATIC:
                    out["share.setFactory"] = jname(i[0]); out["share.setFactory.m"] = i[1]; break
    lz = sorted({cn for cn, c in N.items() for m in c["methods"] if m["name"] == "invoke" and not m["params"]
                 and any(i[0] == "Ljava/util/LinkedHashMap;" and i[1] == "<init>" for i in m["inv"]) and any(i[0] == aown and i[1] == "a" for i in m["inv"])
                 and x.field(c, "b") is not None})
    if lz: out["share.lazyMap"] = ",".join(jname(c) for c in lz[:4])
    if "share.config" in out:
        cd = D(out["share.config"])
        fl = [(cn, m) for cn, c in N.items() for m in c["methods"] if len(m["params"]) == 3 and m["params"][0] == cd and m["params"][1] == LIST]
        if fl: out["share.finalList"] = jname(fl[0][0]); out["share.finalList.m"] = fl[0][1]["name"]
    ft = [(cn, m) for cn, c in N.items() for m in c["methods"] if m["flags"] & ACC_STATIC and m["params"] == (LIST, LIST) and m["ret"] == "Ljava/util/ArrayList;"
          and any(i[0] == aown and i[1] == "a" for i in m["inv"])]
    if ft: out["share.filter"] = jname(ft[0][0]); out["share.filter.m"] = ft[0][1]["name"]
    hs = sorted(cn for cn, c in N.items() if "Landroid/os/Parcelable;" in c["itf"] and {SET, "Z", STR} <= {f[1] for f in x.ifields(c)}
                and len(x.ifields(c)) <= 4 and any(m["name"] == "<init>" and not m["flags"] & ACC_SYNTH and list(m["params"]).count(SET) == 1 for m in c["methods"]))
    print(f"       hiddenSet candidates: {[jname(c) for c in hs]}")
    if 1 <= len(hs) <= 3: out["share.hiddenSet"] = ",".join(jname(c) for c in hs)
    mid = [cn for cn, m in x.users_of_string("native-share-menu") if m["name"] == "<clinit>" and N[cn]["super"] == idt]
    if mid:
        mo = [cn for cn, c in N.items() if c["super"] == dest["super"] and cn != mid[0] and any(
            m["name"] == "a" and not m["params"] and m["ret"] == idt and any(f[0] == mid[0] and f[2] == mid[0] for f in m["fr"]) for m in c["methods"])]
        if mo: out["share.more"] = jname(mo[0])
    return out

def verify_anchors(x, apk_for_res):
    print(f"\n== {x.ver}: unobfuscated anchors")
    ct = x.N.get("Lcom/spotify/player/model/AutoValue_ContextTrack;")
    x.check(ct is not None and any(m["name"] == "uri" and m["ret"] == STR and not m["params"] for m in ct["methods"]), "AutoValue_ContextTrack.uri(): String")
    md = next((m for m in (ct or {}).get("methods", []) if m["name"] == "metadata" and not m["params"]), None)
    x.check(md is not None and (md["ret"] == "Ljava/util/Map;" or any("Ljava/util/Map;" in x.N.get(s, {}).get("itf", []) for s in x.supers(md["ret"]))),
            f"AutoValue_ContextTrack.metadata() is a Map ({md and jname(md['ret'])})")
    allstr = set()
    for c in x.N.values():
        for m in c["methods"]: allstr.update(m["strs"])
    for s in ("canvas.url", "canvas.artist.avatar", "artist_uri", "image_xlarge_url", "artist_browse", "album_browse", "add_to_playlist", "list_add_to_queue",
              "copy-link", "com.spotify.toggle_shuffle", "com.spotify.toggle_repeat"):
        x.check(s in allstr, f"string \"{s}\" present")
    aapt2 = shutil.which("aapt2") or next(iter(sorted(glob.glob("/workspace/tools/android-sdk/build-tools/*/aapt2"), reverse=True)), None)
    if aapt2 and apk_for_res and os.path.exists(apk_for_res):
        target = apk_for_res
        if apk_for_res.endswith((".apkm", ".xapk")):
            target = os.path.join(CACHE, f"base-{x.ver}.apk")
            if not os.path.exists(target): open(target, "wb").write(zipfile.ZipFile(apk_for_res).read("base.apk"))
        dump = subprocess.run([aapt2, "dump", "resources", target], capture_output=True, text=True).stdout
        for r in ("explicit_badge_text_short", "explicit_badge_text_long"):
            x.check(f"string/{r}" in dump, f"resource string/{r}")
    else:
        print("  skip resources (no aapt2 or apk)")

def verify_story_shapes(x):
    """StoryCard.parse (ShareMenuHook) matches story share data by shape only: check that shape still holds."""
    print(f"\n== {x.ver}: story share-data shapes (StoryCard)")
    N = x.N
    inst = lambda c: sorted((f for f in c["fields"] if not f[2] & ACC_STATIC), key=lambda f: f[0])
    def shape(d):
        c = N.get(d)
        if not c: return None
        t = [f[1] for f in inst(c)]
        if t == ["I", "I"]: return "GRADIENT"
        if t == [STR]: return "IMAGE"
        if sorted(t) == sorted([STR, "Z"]): return "VIDEO"
        return None
    img = [cn for cn, c in N.items() if [f[1] for f in inst(c)][:1] == ["Landroid/net/Uri;"] and len(inst(c)) == 7 and "Ljava/util/Map;" in [f[1] for f in inst(c)]]
    if not x.check(len(img) == 1, f"image share data (Uri, …, Map, …) unique: {[jname(c) for c in img]}"): return
    base = N[img[0]]["super"]
    story_bases = [cn for cn, c in N.items() if c["super"] == base and c["flags"] & 0x400]
    if not x.check(len(story_bases) == 1, f"one abstract story base under {jname(base)}: {[jname(c) for c in story_bases]}"): return
    subs = sorted(cn for cn, c in N.items() if c["super"] == story_bases[0])
    got = {}
    for sc in subs:
        w = [f for f in inst(N[sc]) if f[1] in N and N[f[1]]["super"] in N and N[N[f[1]]["super"]]["flags"] & 0x400 and shape(f[1])]
        groups = {}
        for f in w: groups.setdefault(N[f[1]]["super"], []).append(f)
        pair = next((g for g in groups.values() if len(g) == 2), None)
        if pair: got[jname(sc)] = (shape(pair[0][1]), shape(pair[1][1]))
    print(f"       story subclasses: {got}")
    x.check(sorted(got.values()) == sorted([("GRADIENT", "IMAGE"), ("IMAGE", "IMAGE"), ("VIDEO", "IMAGE")]),
            "story data = {gradient, image, video} background + image sticker")

def main():
    inputs = {}
    for a in sys.argv[1:]:
        v, p = a.split("=", 1); inputs[v] = [p]
    if not inputs: inputs = DEFAULT_INPUTS
    tables = read_tables()
    print("tables in HostSymbols.kt:", ", ".join(tables))
    total_bad = 0; checked = 0
    for ver, srcs in inputs.items():
        src = next((s for s in srcs if os.path.exists(s)), None)
        if ver not in tables: print(f"\n!! {ver}: no table in HostSymbols.kt"); total_bad += 1; continue
        if src is None: print(f"\n!! {ver}: no dex input found ({srcs})"); total_bad += 1; continue
        print(f"\n######## {ver}  <- {src}")
        x = Ctx(ver, load_index(src))
        print(f"  {len(x.N)} classes")
        verify_table(x, tables[ver])
        verify_story_shapes(x)
        verify_anchors(x, next((s for s in srcs if s.endswith((".apk", ".apkm", ".xapk")) and os.path.exists(s)), None))
        print(f"\n== {ver}: DexKit discovery fingerprints (unknown-version path) vs table")
        d = discover(x)
        for k, v in sorted(tables[ver].items()):
            got = d.get(k)
            if k in ("share.lazyMap", "share.hiddenSet"):
                x.check(got is not None and v in got.split(","), f"{k}: discovery {got} contains {v}")
            else:
                x.check(got == v, f"{k}: discovery {got} == table {v}")
        print(f"\n== {ver}: {x.ok} ok, {x.bad} failed")
        total_bad += x.bad; checked += 1
    print(f"\nRESULT: {'PASS' if total_bad == 0 and checked else 'FAIL'} ({checked} version(s), {total_bad} failure(s))")
    sys.exit(0 if total_bad == 0 and checked else 1)

if __name__ == "__main__":
    main()
