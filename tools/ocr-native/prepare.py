#!/usr/bin/env python3
"""Prepare hash-locked official source, runtime and model data at build time only.

No package installer or executable is invoked. Android ships no downloader.
--offline requires the verified cache. --host also extracts the pinned host ORT.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import tarfile
import tempfile
import urllib.parse
import urllib.request
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
LOCK = json.loads((HERE / 'dependencies.lock.json').read_text())
HOSTS = {'codeload.github.com', 'raw.githubusercontent.com', 'repo.maven.apache.org',
         'huggingface.co', 'cdn-lfs.huggingface.co', 'cdn-lfs-us-1.hf.co',
         'cas-bridge.xethub.hf.co', 'files.pythonhosted.org',
         # Current HF CDN edges, published in its download docs and meta.json.
         'us.aws.cdn.hf.co', 'us.gcp.cdn.hf.co'}


def check(path, entry):
    return path.is_file() and path.stat().st_size == entry['bytes'] and hashlib.sha256(path.read_bytes()).hexdigest() == entry['sha256']


class OfficialRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, url):
        validate_url(url)
        return super().redirect_request(request, response, code, message, headers, url)


def validate_url(url):
    parsed = urllib.parse.urlparse(url)
    if (parsed.scheme != 'https' or parsed.hostname not in HOSTS or
            parsed.port not in (None, 443) or parsed.username or parsed.password):
        raise RuntimeError('OCR dependency host rejected')


def download(entry, target, offline):
    if check(target, entry):
        return
    if offline:
        raise RuntimeError('OCR dependency cache missing or invalid: ' + entry['name'])
    validate_url(entry['url'])
    request = urllib.request.Request(entry['url'], headers={'User-Agent': 'TapScene-official-build/2'})
    temp = target.with_suffix(target.suffix + '.partial')
    try:
        with urllib.request.build_opener(OfficialRedirects).open(request, timeout=120) as response, temp.open('wb') as out:
            validate_url(response.url)
            total = 0
            while block := response.read(65536):
                total += len(block)
                if total > entry['bytes']:
                    raise RuntimeError('OCR dependency exceeds locked length')
                out.write(block)
        if not check(temp, entry):
            raise RuntimeError('OCR dependency digest mismatch')
        os.replace(temp, target)
    finally:
        temp.unlink(missing_ok=True)


def extract_source(archive, target):
    target.mkdir(parents=True)
    with tarfile.open(archive, 'r:gz') as source:
        members = source.getmembers()
        if len(members) > 20000:
            raise RuntimeError('Source archive has too many entries')
        total = 0
        for member in members:
            path = PurePosixPath(member.name)
            if path.is_absolute() or '..' in path.parts or not (member.isdir() or member.isfile()):
                raise RuntimeError('Unsafe source archive entry')
            total += member.size
            if total > 224 * 1024 * 1024:
                raise RuntimeError('Source archive too large')
            if len(path.parts) < 2:
                continue
            dest = target.joinpath(*path.parts[1:])
            if member.isdir():
                dest.mkdir(parents=True, exist_ok=True)
            else:
                dest.parent.mkdir(parents=True, exist_ok=True)
                with source.extractfile(member) as inp, dest.open('wb') as out:
                    shutil.copyfileobj(inp, out)


def extract_locked(zip_file, member, target, entry):
    info = zip_file.getinfo(member)
    if info.file_size != entry['bytes']:
        raise RuntimeError('Archive member size mismatch')
    target.parent.mkdir(parents=True, exist_ok=True)
    with zip_file.open(info) as inp, target.open('wb') as out:
        shutil.copyfileobj(inp, out)
    if not check(target, entry):
        raise RuntimeError('Archive member hash mismatch')


def replace_exact(path, old, new):
    text = path.read_text()
    if text.count(old) != 1:
        raise RuntimeError('Pinned OpenCV patch mismatch')
    path.write_text(text.replace(old, new))


def patch_profile(source):
    # Fail closed if any optional CMake dependency unexpectedly tries to download.
    replace_exact(source / 'cmake/OpenCVDownload.cmake', 'function(ocv_download)\n',
                  'function(ocv_download)\n  message(FATAL_ERROR "TapScene forbids unpinned OpenCV downloads")\n')
    # OpenCV otherwise always discovers/builds zlib, even with no imgcodecs module.
    path = source / 'cmake/OpenCVFindLibsGrfmt.cmake'
    text = path.read_text()
    start, end = text.index('# --- zlib (required) ---'), text.index('# --- libavif (optional) ---')
    replace_exact(path, text[start:end], '# TapScene raw-pixel profile: no zlib/archive/persistence compression.\n\n')
    replace_exact(source / 'modules/core/src/persistence.hpp', '#define USE_ZLIB 1', '#define USE_ZLIB 0 // TapScene raw pixels only')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--offline', action='store_true')
    parser.add_argument('--host', action='store_true', help='also prepare x86_64 Linux host-only ORT')
    parser.add_argument('--cache', type=Path, default=ROOT / 'android/app/build/ocr-downloads')
    parser.add_argument('--output', type=Path, default=ROOT / 'android/app/build/ocr')
    args = parser.parse_args()
    args.cache.mkdir(parents=True, exist_ok=True)
    downloads = [LOCK['opencv'], LOCK['ort'], LOCK['dictionary_source']]
    downloads += [entry for entry in LOCK['models']['files'] if 'url' in entry]
    if args.host:
        downloads.append(LOCK['host_ort'])
    for entry in downloads:
        download(entry, args.cache / entry['name'], args.offline)
    dictionary = next(entry for entry in LOCK['models']['files'] if entry['name'] == 'characters.txt')
    if not check(HERE / 'characters.txt', dictionary):
        raise RuntimeError('Derived official character dictionary mismatch')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='ocr-prepare-', dir=args.output.parent) as temp:
        prepared = Path(temp) / 'prepared'
        source = prepared / 'src/opencv'
        extract_source(args.cache / LOCK['opencv']['name'], source)
        patch_profile(source)
        with zipfile.ZipFile(args.cache / LOCK['ort']['name']) as archive:
            for entry in LOCK['ort']['headers']:
                extract_locked(archive, 'headers/' + entry['name'], prepared / 'include/onnxruntime' / entry['name'], entry)
            for entry in LOCK['ort']['native']:
                abi = PurePosixPath(entry['path']).parts[1]
                extract_locked(archive, entry['path'], prepared / 'ort' / abi / 'libonnxruntime.so', entry)
        if args.host:
            entry = LOCK['host_ort']
            with zipfile.ZipFile(args.cache / entry['name']) as archive:
                target = prepared / 'ort/host/libonnxruntime.so.1.31.0'
                extract_locked(archive, entry['library_path'], target, {'bytes': entry['library_bytes'], 'sha256': entry['library_sha256']})
                (target.parent / 'libonnxruntime.so.1').symlink_to(target.name)
                (target.parent / 'libonnxruntime.so').symlink_to(target.name)
        assets = prepared / 'assets/ocr'
        assets.mkdir(parents=True)
        for entry in LOCK['models']['files']:
            source_file = args.cache / entry['name'] if 'url' in entry else HERE / entry['name']
            shutil.copyfile(source_file, assets / entry['name'])
        shutil.copytree(HERE / 'licenses', assets / 'licenses')
        shutil.copyfile(HERE / 'dependencies.lock.json', prepared / 'dependencies.lock.json')
        if args.output.exists():
            shutil.rmtree(args.output)
        os.replace(prepared, args.output)
    print('OCR_SOURCE_AND_MODEL_PREPARATION_PASSED')


if __name__ == '__main__':
    main()
