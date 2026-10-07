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


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--write", action="store_true")
    ap.add_argument("--harvest", action="store_true")
    args = ap.parse_args()

    extra_by_file = {}
    if args.harvest:
        extra_by_file["ankaraITCompanies.json"] = harvest_hacettepe()
        print(f"harvested {len(extra_by_file['ankaraITCompanies.json'])} Ankara candidates from Hacettepe Teknokent")

    report = [f"# Data refresh report ({TODAY})\n"]
    for path in sorted(glob.glob(os.path.join(ROOT, "*ITCompanies.json"))):
        name = os.path.basename(path)
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
