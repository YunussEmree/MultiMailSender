#!/usr/bin/env python3
"""Cleans and refreshes the company lists in internalData/.

  python tools/refresh_data.py            # dry run, prints a report
  python tools/refresh_data.py --write    # rewrites the JSON files + internalData/REPORT.md
  python tools/refresh_data.py --write --harvest   # also pulls new Ankara IT firms from Hacettepe Teknokent

What it does per file:
  * normalises the schema (id, companyMail, parameters{companyName, companyNumber, companyWebsite, verifiedAt})
  * normalises phone numbers to "+90 312 299 21 64"
  * removes duplicate mail addresses inside a file
  * drops addresses whose domain has neither an MX nor an A record (mail cannot be delivered)
  * stamps every kept entry with the verification date

Requires: requests, dnspython
"""
import argparse
import glob
import json
import os
import re
import sys
from concurrent.futures import ThreadPoolExecutor
from datetime import date
from html import unescape

import dns.exception
import dns.resolver
import requests
import urllib3

urllib3.disable_warnings()

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "internalData")
TODAY = date.today().isoformat()
EMAIL_RE = re.compile(r"^[^@\s]+@[^@\s]+\.[A-Za-z]{2,}$")

resolver = dns.resolver.Resolver()
resolver.lifetime = 6
resolver.timeout = 3
_dns_cache = {}


def domain_status(domain):
    """'ok' (MX or A exists), 'dead' (NXDOMAIN / nothing), 'unknown' (timeout)."""
    if domain in _dns_cache:
        return _dns_cache[domain]
    result = "dead"
    for rtype in ("MX", "A", "AAAA"):
        try:
            resolver.resolve(domain, rtype)
            result = "ok"
            break
        except (dns.resolver.NXDOMAIN, dns.resolver.NoAnswer, dns.resolver.NoNameservers):
            continue
        except dns.exception.Timeout:
            result = "unknown"
    _dns_cache[domain] = result
    return result


def normalize_phone(raw):
    s = (raw or "").strip()
    if not s:
        return ""
    digits = re.sub(r"\D", "", s)
    if digits.startswith("00"):
        digits = digits[2:]
    if digits.startswith("90") and len(digits) == 12:
        digits = digits[2:]
    elif digits.startswith("0") and len(digits) == 11:
        digits = digits[1:]
    if len(digits) == 10:
        return f"+90 {digits[:3]} {digits[3:6]} {digits[6:8]} {digits[8:]}"
    return s  # unknown shape: keep as written


def normalize_site(raw):
    s = (raw or "").strip()
    if not s:
        return ""
    if not re.match(r"^https?://", s, re.I):
        s = "https://" + s
    return s.rstrip("/")


def clean_entry(c):
    p = c.get("parameters") or {}
    out = {
        "companyName": (p.get("companyName") or "").strip(),
        "companyNumber": normalize_phone(p.get("companyNumber")),
        "companyWebsite": normalize_site(p.get("companyWebsite")),
    }
    if (p.get("companyMobil") or "").strip():
        out["companyMobil"] = normalize_phone(p["companyMobil"])
    out["verifiedAt"] = TODAY
    return (c.get("companyMail") or "").strip().lower(), out


def process_file(path, extra=()):
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    removed, seen, kept = [], set(), []

    entries = [clean_entry(c) for c in data["companyData"]] + list(extra)
    domains = {m.split("@")[1] for m, _ in entries if EMAIL_RE.match(m)}
    with ThreadPoolExecutor(24) as ex:
        status = dict(zip(domains, ex.map(domain_status, domains)))

    for mail, params in entries:
        if not EMAIL_RE.match(mail):
            removed.append((mail, params["companyName"], "invalid address"))
        elif mail in seen:
            removed.append((mail, params["companyName"], "duplicate in file"))
        elif status[mail.split("@")[1]] == "dead":
            removed.append((mail, params["companyName"], "domain does not resolve"))
        else:
            seen.add(mail)
            kept.append({"companyMail": mail, "parameters": params})

    # a phone number shared by several companies is a switchboard (e.g. the technopark's), not theirs
    phone_counts = {}
    for c in kept:
        ph = c["parameters"]["companyNumber"]
        phone_counts[ph] = phone_counts.get(ph, 0) + 1
    for c in kept:
        if c["parameters"]["companyNumber"] and phone_counts[c["parameters"]["companyNumber"]] > 3:
            c["parameters"]["companyNumber"] = ""

    for i, c in enumerate(kept):
        c["id"] = i
    data["companyData"] = [{"id": c["id"], "companyMail": c["companyMail"], "parameters": c["parameters"]} for c in kept]
    return data, removed, len(entries) - len(extra)


def tr_title(name):
    """Title-case an ALL-CAPS Turkish name (I -> ı, İ -> i before capitalising)."""
    name = name.replace("̇", "")
    low = name.replace("I", "ı").replace("İ", "i").lower()
    return " ".join(w[:1].replace("i", "İ").replace("ı", "I").upper() + w[1:] if w else w for w in low.split(" "))


def registered_domain(host):
    host = re.sub(r"^https?://", "", host.lower()).split("/")[0].split("@")[-1]
    parts = host.split(".")
    if len(parts) >= 3 and parts[-2] in ("com", "org", "net", "gov", "edu") and parts[-1] == "tr":
        return ".".join(parts[-3:])
    return ".".join(parts[-2:])


# ---------------------------------------------------------------- Hacettepe Teknokent harvest
HAC = "https://hacettepeteknokent.com.tr"


def harvest_hacettepe():
    s = requests.Session()
    s.headers["User-Agent"] = "Mozilla/5.0"
    page = s.get(f"{HAC}/tr/firma_rehberi/bilgisayar_ve_iletisim_teknolojileri-16", timeout=30).text
    links = sorted(set(re.findall(r'href="(/tr/firma/[^"]+)"', page)))
    found = []

    def one(link):
        try:
            h = s.get(HAC + link, timeout=30).text
        except requests.RequestException:
            return None
        text = unescape(re.sub(r"\s+", " ", re.sub(r"<script.*?</script>|<style.*?</style>|<[^>]+>", " ", h, flags=re.S)))
        tail = text.split("Ana Sayfa İletişim Hacettepe Üniversitesi Kuluçka Merkezi HT-TTM TR | EN", 1)[-1]
        tail = tail.split("Firma Rehberi İletişim Ana Sayfa İletişim Hacettepe Üniversitesi Kuluçka Merkezi HT-TTM TR EN", 1)[-1].strip()
        m = re.match(r"(.+?)\s+(\(\d{3}\)[\d\s-]+|0?\s?\(?\d{3}\)?[\d\s-]{7,})\s+(https?://\S+)?\s*([\w.+-]+@[\w.-]+\.\w+)?\s*(.*)", tail)
        if not m:
            return None
        name, phone, site, mail, rest = m.groups()
        if not mail or "hacettepeteknokent" in mail:
            return None
        if "ankara" not in rest.lower() and "çankaya" not in rest.lower():
            return None  # e.g. Istanbul branch offices registered at the park
        # only keep company-domain addresses (drops personal gmail/hotmail boxes and unrelated sites)
        if not site or registered_domain(mail) != registered_domain(site):
            return None
        # and only role mailboxes, never a named person's address
        if not re.match(r"^(info|iletisim|contact|hello|hi|hr|ik|kariyer|career|careers|bilgi|mail|office|ofis|merhaba)$", mail.split("@")[0].lower()):
            return None
        return mail.lower(), {
            "companyName": tr_title(name.strip()) if name.strip().isupper() else name.strip(),
            "companyNumber": normalize_phone(phone),
            "companyWebsite": normalize_site(site),
            "verifiedAt": TODAY,
        }

    with ThreadPoolExecutor(8) as ex:
        for r in ex.map(one, links):
            if r:
                found.append(r)
    return found


# ---------------------------------------------------------------- shared helpers for harvesting
ROLE_RE = re.compile(r"^(info|iletisim|contact|hello|hi|hr|ik|kariyer|career|careers|bilgi|mail|office|ofis|merhaba)$")
ROLE_ORDER = ["hr", "ik", "kariyer", "career", "careers", "info", "iletisim", "contact", "bilgi", "hello", "mail", "office", "ofis", "merhaba", "hi"]
CONTACT_PATHS = ["", "/iletisim", "/contact", "/tr/iletisim", "/en/contact", "/iletisim.html", "/contact-us", "/kurumsal/iletisim", "/tr/contact"]
_http = requests.Session()
_http.headers["User-Agent"] = "Mozilla/5.0"


def http_get(url, timeout=8):
    try:
        r = _http.get(url, timeout=timeout, verify=False)
        if r.status_code != 200:
            return ""
        try:
            return r.content.decode("utf-8")
        except UnicodeDecodeError:
            return r.text
    except requests.RequestException:
        return ""


def decode_cf(hexstr):
    key = int(hexstr[:2], 16)
    return "".join(chr(int(hexstr[i:i + 2], 16) ^ key) for i in range(2, len(hexstr), 2))


def emails_in(html):
    html = unescape(html)
    found = [decode_cf(m) for m in re.findall(r'data-cfemail="([0-9a-f]+)"', html)]
    html = re.sub(r"\s*\[\s*(?:at|AT)\s*\]\s*", "@", html)
    found += re.findall(r"[\w.+-]+@[\w-]+(?:\.[\w-]+)+", html)
    return [e.lower().strip(".") for e in found if not re.search(r"\.(png|jpe?g|gif|svg|webp|css|js)$", e, re.I)]


def best_role_mail(candidates, site):
    """Pick a role mailbox on the company's own domain, preferring HR/info style names."""
    dom = registered_domain(site)
    ok = {e for e in candidates if EMAIL_RE.match(e) and registered_domain(e.split("@")[1]) == dom and ROLE_RE.match(e.split("@")[0])}
    for role in ROLE_ORDER:
        for e in sorted(ok):
            if e.split("@")[0] == role:
                return e
    return ""


def enrich_from_site(site):
    """Visit homepage + contact pages. Returns (mail, phone, title)."""
    base = normalize_site(site)
    mails, phone, title = [], "", ""
    for path in CONTACT_PATHS:
        h = http_get(base + path)
        if not h:
            continue
        mails += emails_in(h)
        if not phone:
            m = re.search(r'href="tel:([+\d\s().-]{10,20})"', h)
            phone = normalize_phone(m.group(1)) if m else ""
        if not title:
            m = re.search(r"<title>(.*?)</title>", h, re.S | re.I)
            title = re.sub(r"\s+", " ", unescape(m.group(1))).strip() if m else ""
        if best_role_mail(mails, base):
            break
    return best_role_mail(mails, base), phone, title


def clean_title(t, domain):
    t = re.split(r"\s[|–—-]\s", t)[0].strip()
    generic = re.search(r"ana ?sayfa|home|welcome|hoş ?geldiniz|index|^untitled", t, re.I)
    return t if 3 <= len(t) <= 40 and not generic else domain.split(".")[0].capitalize()


# ---------------------------------------------------------------- OSTIM Teknopark (Ankara)
def harvest_ostim():
    base = "https://ostimteknopark.com.tr/tr/company/firmalar-119/"
    index = http_get("https://ostimteknopark.com.tr/tr/firmalar-119", 30)
    names = {u: unescape(n).strip() for u, n in re.findall(
        r'href="(https://ostimteknopark\.com\.tr/tr/company/firmalar-119/[^"]+)".*?<span class="title">(.*?)</span>', index, re.S)}
    links = sorted(names)

    def one(url):
        h = http_get(url, 25)
        if not h:
            return None
        name = names.get(url, "")
        mails = emails_in(h)
        site = (re.findall(r'href="(https?://(?!ostimteknopark)[^"]+)"', h) or [""])
        site = next((u for u in site if not re.search(r"facebook|instagram|linkedin|twitter|youtube|google|cloudflare|wa\.me|api\.whatsapp", u)), "")
        phone = re.search(r'href="tel:([^"]+)"', h)
        return {"name": unescape(name), "website": site, "mails": mails, "phone": phone.group(1) if phone else ""}

    with ThreadPoolExecutor(12) as ex:
        raw = [r for r in ex.map(one, links) if r]
    return raw


# ---------------------------------------------------------------- Bilkent Cyberpark (Ankara)
def harvest_cyberpark():
    sites = set()
    for n in range(1, 40):
        h = http_get(f"https://www.cyberpark.com.tr/firma-arsiv/{n}", 25)
        for u in re.findall(r'href="(https?://[^"]+)"', h):
            if not re.search(r"cyberpark|google|facebook|instagram|linkedin|twitter|youtube|mkk\.com|bilkent\.edu|w3\.org", u):
                sites.add(u.rstrip("/"))
    return [{"name": "", "website": u, "mails": [], "phone": ""} for u in sorted(sites)]


# ---------------------------------------------------------------- name -> website guessing
GENERIC_WORDS = {
    "a.ş.", "a.s.", "aş", "as", "ltd", "ltd.", "şti", "şti.", "sti", "anonim", "şirketi", "sirketi", "limited", "sanayi", "ticaret",
    "san.", "tic.", "san", "tic", "ve", "ve.", "ar-ge", "arge", "danışmanlık", "danismanlik", "hizmetleri", "hizmet", "bilişim",
    "bilisim", "yazılım", "yazilim", "teknolojileri", "teknoloji", "teknolojiler", "sistemleri", "sistem", "mühendislik", "muhendislik",
    "dış", "dis", "iç", "ic", "pazarlama", "ithalat", "ihracat", "elektronik", "otomasyon", "çözümleri", "cozumleri", "ürünleri",
    "urunleri", "eğitim", "egitim", "tasarım", "tasarim", "geliştirme", "gelistirme", "ürün", "urun", "bilgi", "iletişim", "iletisim",
    "dijital", "digital", "software", "tech", "technology", "technologies", "systems", "solutions", "ar", "ge", "ve/veya", "-", "&",
}
TURKISH_FOLD = str.maketrans("çğıöşüÇĞİÖŞÜâîû", "cgiosuCGIOSUaiu")
TLDS = [".com", ".com.tr", ".net", ".io", ".tech", ".org", ".co", ".dev", ".app"]


def fold(text):
    return re.sub(r"[^a-z0-9]", "", (text or "").translate(TURKISH_FOLD).lower())


def site_candidates(name):
    words = [w for w in re.split(r"[\s/]+", re.sub(r"[()',.]", " ", name)) if w]
    brand = []
    for w in words:
        if w.lower() in GENERIC_WORDS:
            break
        brand.append(fold(w))
    brand = [b for b in brand if b]
    if not brand:
        brand = [fold(words[0])] if words and fold(words[0]) else []
    if not brand:
        return [], ""
    bases = []
    first, joined = brand[0], "".join(brand[:2])
    for b in (joined, first, first + "yazilim", first + "bilisim", first + "teknoloji", first + "tech"):
        if len(b) >= 3 and b not in bases:
            bases.append(b)
    return [b + t for b in bases for t in TLDS], first


def guess_site(name):
    """Finds the company's own website by trying plausible domains; accepts one whose page mentions the brand."""
    cands, brand = site_candidates(name)
    for dom in cands:
        if domain_status(dom) != "ok":
            continue
        for scheme in ("https://www.", "https://"):
            h = http_get(scheme + dom, 8)
            if h:
                head = fold(re.sub(r"<script.*?</script>|<style.*?</style>", "", h[:60000], flags=re.S | re.I))
                if brand in head and len(brand) >= 3:
                    return "https://" + dom
                break
    return ""


IT_NAME_RE = re.compile(r"yaz[ıi]l[ıi]m|bili[şs]im|software|dijital|digital|bilgi\s*tekn|bulut|cloud|siber|cyber|mobil|oyun|games?|yapay\s*zeka|\bai\b|\bit\b|data|veri|otomasyon|sistem|tech|teknoloji|network|a[ğg]\s|internet|web", re.I)


def is_it(name, category=""):
    return bool(IT_NAME_RE.search(name)) or bool(re.search(r"bilgisayar|yaz[ıi]l[ıi]m|bili[şs]im|telekom|medya ve", category, re.I))


# ---------------------------------------------------------------- technopark directories
def _utf(url, timeout=30):
    return http_get(url, timeout)


def harvest_sivas():
    h = _utf("https://cumhuriyetteknokent.com/firmalar")
    return [{"name": unescape(n).strip(), "website": "", "mails": [], "phone": "", "category": ""}
            for n in re.findall(r'class="firma-page-title">\s*(.*?)\s*</div>', h, re.S)]


def harvest_yildiz():
    out = []
    h = _utf("https://www.yildizteknopark.com.tr/firmalarimiz?per_page=500")
    for url, name in re.findall(r'<a href="(https?://[^"]+)"[^>]*target="_blank"[^>]*>.*?service-block_six-text">(.*?)</div>', h, re.S):
        if re.search(r"yildizteknopark|facebook|instagram|linkedin|twitter|youtube", url):
            continue
        out.append({"name": unescape(re.sub(r"\s+", " ", name)).strip(), "website": url.replace("http://https://", "https://"),
                    "mails": [], "phone": "", "category": ""})
    return out


def harvest_marmara():
    h = _utf("https://marmarateknokent.com.tr/firmalar/")
    seen, out = set(), []
    for url in re.findall(r'href="(https?://[^"#]+)#new_tab"', h):
        if registered_domain(url) not in seen:
            seen.add(registered_domain(url))
            out.append({"name": "", "website": url, "mails": [], "phone": "", "category": ""})
    return out


def harvest_ari():
    out = []
    for page in range(1, 25):
        h = _utf(f"https://www.ariteknokent.com.tr/tr/teknoloji-firmalari/teknokentli-firmalar?page={page}")
        cards = re.findall(r'<div class="card company-card"[^>]*title="([^"]+)".*?card-text">(.*?)</p>', h, re.S)
        if not cards:
            break
        out += [{"name": unescape(n).strip(), "website": "", "mails": [], "phone": "", "category": unescape(c).strip()} for n, c in cards]
    return out


def harvest_antalya():
    index = _utf("https://www.antalyateknokent.com.tr/tr/firmalar")
    pages = sorted(set(re.findall(r'href="(https://www\.antalyateknokent\.com\.tr/tr/[^"]*(?:binasi|merkezi)[^"]*)"', index)))
    out, seen = [], set()
    for url in pages:
        h = _utf(url)
        text = unescape(re.sub(r"\s+", " ", re.sub(r"<script.*?</script>|<style.*?</style>|<[^>]+>", " ", h, flags=re.S)))
        m = re.search(r"Firmalar\s+(?:AR-GE\s+\d+\s+Binası|[^ ]+(?: [^ ]+){0,4}?)\s+(.*?)\s+Adres Bilgisi", text)
        body = m.group(1) if m else ""
        for name in [n.strip() for n in body.split("Devamı")]:
            if 3 <= len(name) <= 120 and name.lower() not in seen:
                seen.add(name.lower())
                out.append({"name": name, "website": "", "mails": [], "phone": "", "category": ""})
    return out


def harvest_izmir():
    """Teknopark Izmir lists name, website, e-mail and phone for every firm on one page."""
    h = _utf("https://www.teknoparkizmir.com.tr/tr/firma-listesi/", 60)
    out = []
    for block in h.split('class="col-md-12 firmaListe holder"')[1:]:
        head = re.match(r'[^>]*data-filter="([^"]*)"[^>]*data-name="([^"]*)"', block)
        if not head:
            continue
        sector, name = unescape(head.group(1)), unescape(head.group(2)).strip()
        text = unescape(re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " ", block)))
        mails = emails_in(text)
        site = re.search(r"((?:https?://)?(?:www\.)?[\w-]+(?:\.[\w-]+)+(?:/\S*)?)\s+[\w.+-]+@", text)
        site = site.group(1) if site and "@" not in site.group(1) else ""
        phone = re.search(r"(0?\s?\(?\d{3}\)?[\s-]?\d{3}[\s-]?\d{2}[\s-]?\d{2})", text)
        out.append({"name": name, "website": site, "mails": mails, "phone": phone.group(1) if phone else "", "category": sector})
    return [r for r in out if is_it(r["name"], r["category"]) or re.search(r"yaz[ıi]l[ıi]m|bili[şs]im", r["category"], re.I)]


def harvest_manisa():
    h = _utf("https://manisateknokent.com.tr/firmalar", 60)
    seen, out = set(), []
    for name, cat in re.findall(r'<div title="([^"]+)"[^>]*>[^<]*</div><div title="([^"]+)"', h):
        name = unescape(name).strip()
        if name.lower() not in seen:
            seen.add(name.lower())
            out.append({"name": name, "website": "", "mails": [], "phone": "", "category": unescape(cat)})
    return out


PARKS = {
    "sivasITCompanies.json": [("Cumhuriyet Teknokent", harvest_sivas)],
    "istanbulITCompanies.json": [("Yildiz Teknopark", harvest_yildiz), ("ITU Ari Teknokent", harvest_ari)],
    "izmirITCompanies.json": [("Teknopark Izmir", harvest_izmir)],
    "manisaITCompanies.json": [("Manisa Teknokent", harvest_manisa)],
    "antalyaITCompanies.json": [("Antalya Teknokent", harvest_antalya)],
    "kocaeliITCompanies.json": [("Marmara Teknokent", harvest_marmara)],
}


def candidates_to_entries(raw):
    """Turns harvested {name, website, mails, phone} into (mail, params) using the company's own site for missing data."""
    def one(r):
        site, mail, phone, name = r["website"], "", r["phone"], r["name"]
        if not site and name:
            if not is_it(name, r.get("category", "")):
                return None
            site = guess_site(name)
        if site:
            m2, p2, t2 = enrich_from_site(site)
            mail = best_role_mail(r["mails"] + ([m2] if m2 else []), site) if r["mails"] or m2 else ""
            phone = phone or p2
            name = name or clean_title(t2, registered_domain(site))
        if not mail:
            # directory-listed role mailbox on any domain (e.g. OSTIM lists one)
            listed = [e for e in r["mails"] if EMAIL_RE.match(e) and ROLE_RE.match(e.split("@")[0])]
            mail = listed[0] if listed else ""
        if not mail:
            return None
        return mail, {"companyName": tr_title(name) if name.isupper() else name, "companyNumber": normalize_phone(phone),
                      "companyWebsite": normalize_site(site), "verifiedAt": TODAY}

    # a phone shared by many entries is the park's switchboard, not the company's
    counts = {}
    for r in raw:
        counts[r["phone"]] = counts.get(r["phone"], 0) + 1
    for r in raw:
        if counts[r["phone"]] > 2:
            r["phone"] = ""
    with ThreadPoolExecutor(16) as ex:
        return [e for e in ex.map(one, raw) if e]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--write", action="store_true")
    ap.add_argument("--harvest", action="store_true", help="Ankara sources (Hacettepe, OSTIM, Cyberpark)")
    ap.add_argument("--parks", help="comma separated city file prefixes to harvest, e.g. sivas,istanbul,kocaeli")
    args = ap.parse_args()

    extra_by_file = {}
    if args.harvest:
        ankara = harvest_hacettepe()
        print(f"Hacettepe Teknokent: {len(ankara)}")
        ostim = candidates_to_entries(harvest_ostim())
        print(f"OSTIM Teknopark: {len(ostim)}")
        cyber = candidates_to_entries(harvest_cyberpark())
        print(f"Bilkent Cyberpark: {len(cyber)}")
        extra_by_file["ankaraITCompanies.json"] = ankara + ostim + cyber

    for fname, sources in PARKS.items():
        if not args.parks or fname.split("ITCompanies")[0] not in args.parks.split(","):
            continue
        path = os.path.join(ROOT, fname)
        if not os.path.exists(path):  # new city: start from the template of an existing file
            with open(os.path.join(ROOT, "sivasITCompanies.json"), encoding="utf-8") as f:
                tpl = json.load(f)
            tpl["companyData"] = []
            with open(path, "w", encoding="utf-8", newline="\n") as f:
                json.dump(tpl, f, ensure_ascii=False, indent=2)
        extra_by_file.setdefault(fname, [])
        for label, fn in sources:
            raw = fn()
            got = candidates_to_entries(raw)
            print(f"{label}: {len(raw)} listed, {len(got)} with a usable mailbox", flush=True)
            extra_by_file[fname] += got

    report = [f"# Data refresh report ({TODAY})\n"]
    for path in sorted(glob.glob(os.path.join(ROOT, "*ITCompanies.json"))):
        name = os.path.basename(path)
        if args.parks and name.split("ITCompanies")[0] not in args.parks.split(","):
            continue
        data, removed, before = process_file(path, extra_by_file.get(name, ()))
        after = len(data["companyData"])
        line = f"{name}: {before} -> {after} ({len(removed)} removed)"
        print(line)
        report.append(f"## {name}\n\n{before} existing entries -> {after} kept, {len(removed)} removed.\n")
        if removed:
            report.append("| Address | Company | Reason |\n|---|---|---|")
            report += [f"| {m} | {n} | {why} |" for m, n, why in removed]
            report.append("")
        if args.write:
            with open(path, "w", encoding="utf-8", newline="\n") as f:
                json.dump(data, f, ensure_ascii=False, indent=2)
                f.write("\n")
    if args.write:
        with open(os.path.join(ROOT, "REPORT.md"), "w", encoding="utf-8", newline="\n") as f:
            f.write("\n".join(report) + "\n")


if __name__ == "__main__":
    sys.exit(main())
