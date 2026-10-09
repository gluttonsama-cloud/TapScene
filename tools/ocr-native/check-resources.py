#!/usr/bin/env python3
"""Build only deterministic synthetic raw-pixel fixtures and measure the native core.

No decoder, downloaded program, Python OCR, truth input or per-word crop is passed
to the engine. Linux wait4 reports peak RSS for each separate child process.
The long-screen result is reported, not silently included in the six-case gate.
"""
import argparse,gzip,hashlib,importlib.util,json,os,resource,time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
parser=argparse.ArgumentParser(description="Linux host-only synthetic OCR resource report, never user media")
parser.add_argument('--native-runner',type=Path,required=True)
parser.add_argument('--model-dir',type=Path,required=True)
parser.add_argument('--output',type=Path,required=True)
args=parser.parse_args()
OUT=args.output.resolve();OUT.mkdir(parents=True,exist_ok=True)
manifest=json.loads((ROOT/'tools/ocr-checks/fixtures/manifest.json').read_text())
runner=args.native_runner.resolve();models=args.model_dir.resolve()
# Fixed canvas construction: six equal-height (400-row) panels, source pixels unchanged.
# The same bottom 40 rows are omitted from every panel; no text ROI/model-guided crop.
# Inference receives only the full 1080x2400 RGB-equivalent PGM and fixed model directory.
w,h=1080,2400; pixels=bytearray([255])*(w*h); lines=[]
for i,f in enumerate(manifest['fixtures']):
 raw=gzip.decompress((ROOT/'tools/ocr-checks/fixtures'/f['file']).read_bytes());assert hashlib.sha256(raw).hexdigest()==f['pgm_sha256']
 header=f"P5\n{f['width']} {f['height']}\n255\n".encode();assert raw.startswith(header)
 data=raw[len(header):];x0=(1080-f['width'])//2;y0=i*400
 for row in range(400): pixels[(y0+row)*w+x0:(y0+row)*w+x0+f['width']]=data[row*f['width']:(row+1)*f['width']]
 for l in f['lines']:
  a,b,c,d=l['box'];assert d<=400
  lines.append({'text':l['text'],'box':[a+x0,b+y0,c+x0,d+y0],'font_px':l['font_px'],'panel':f['name']})
long={'name':'long-screen-native-budget','group':'resource-quality','width':w,'height':h,'lines':lines}
(OUT/'long-screen.pgm').write_bytes(b'P5\n1080 2400\n255\n'+pixels)
(OUT/'long-screen-truth.json').write_text(json.dumps(long,ensure_ascii=False,indent=2)+'\n')
for name,dims in [('max-blank',(1080,2400)),('square-blank',(1280,1280))]:
 width,height=dims;(OUT/f'{name}.pgm').write_bytes(f'P5\n{width} {height}\n255\n'.encode()+bytes([255])*(width*height))
spec=importlib.util.spec_from_file_location('metrics',ROOT/'tools/ocr-checks/check-ocr.py');metrics=importlib.util.module_from_spec(spec);spec.loader.exec_module(metrics)
results=[]
for name in ['max-blank','square-blank','long-screen']:
 stdout=OUT/f'{name}.stdout';stderr=OUT/f'{name}.stderr';start=time.monotonic();pid=os.fork()
 if pid==0:
  resource.setrlimit(resource.RLIMIT_AS,(1536*1024**2,1536*1024**2))
  resource.setrlimit(resource.RLIMIT_CPU,(30,30))
  os.dup2(os.open(stdout,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600),1)
  os.dup2(os.open(stderr,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600),2)
  os.execv(runner,[str(runner),str(OUT/f'{name}.pgm'),str(models)])
 _,status,usage=os.wait4(pid,0)
 r={'case':name,'elapsed_ms':(time.monotonic()-start)*1000,'peak_rss_kib':usage.ru_maxrss,'exit_code':os.waitstatus_to_exitcode(status),'stderr_bytes':stderr.stat().st_size}
 if name=='long-screen' and r['exit_code']==0:
  r['score']=metrics.score(long,metrics.parse_tsv(stdout.read_text(),1,1080,2400))
 results.append(r)
report={'scope':'Final same C++ core synthetic resource check, not device acceptance','detector_max_side':1280,'detector_max_pixels':786432,'construction':'Six source fixtures top 400 rows, fixed 400-row panel grid, centered unchanged source pixels; full long-screen input only; truth used only after inference for scoring','cases':results}
(OUT/'resource-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
for r in results:
 print(r['case'],round(r['elapsed_ms'],1),r['peak_rss_kib'],'exit',r['exit_code'],'stderr',r['stderr_bytes'])
 if 'score' in r:
  print('CER',r['score']['cer'],'unmatched',r['score']['unmatched_words'])
  for l in r['score']['lines']:print(l)

raise SystemExit(1 if any(r['exit_code'] != 0 or r['stderr_bytes'] != 0 for r in results) else 0)
