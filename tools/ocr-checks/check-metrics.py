#!/usr/bin/env python3
"""Small deterministic checks of measurement logic; does not certify OCR quality."""
import importlib.util
from pathlib import Path

spec = importlib.util.spec_from_file_location("ocr_checks", Path(__file__).with_name("check-ocr.py"))
ocr = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ocr)

assert ocr.normalize(" 创建 演示\n") == "创建演示"
assert ocr.normalize("PNG") != ocr.normalize("png")
assert ocr.distance("创建演示", "创建提示")["substitutions"] == 1
assert ocr.distance("下一步", "") == dict(edits=3, substitutions=0, deletions=3, insertions=0)
assert ocr.distance("", "Q")["insertions"] == 1

header = "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\n"
word = "5\t1\t1\t1\t1\t1\t20\t20\t40\t20\t90\t下一步\n"
parsed = ocr.parse_tsv(header + word, 2, 100, 100)
assert parsed[0]["box"] == [10, 10, 30, 20]
assert parsed[0]["normalized_box"] == [0.1, 0.1, 0.2, 0.1]
fixture = {"lines": [{"text": "下一步", "box": [10, 10, 30, 20]}]}
assert ocr.score(fixture, parsed)["cer"] == 0
assert ocr.score(fixture, parsed)["lines"][0]["box_iou"] == 1
assert ocr.score(fixture, [])["deletions"] == 3
assert ocr.score({"lines": []}, parsed)["false_positive_characters"] == 3
assert len(ocr.conservative_candidates(parsed)) == 1
assert not ocr.conservative_candidates([{**parsed[0], "text": "Q"}])
assert not ocr.conservative_candidates([{**parsed[0], "text": "《《《"}])
assert not ocr.conservative_candidates([{**parsed[0], "confidence": 79}])
assert not ocr.conservative_candidates([{**parsed[0], "text": "x" * 81}])
words = [{**parsed[0], "text": "创建"}, {**parsed[0], "text": "演示"}]
assert ocr.conservative_candidates(words)[0]["text"] == "创建演示"
words = [{**parsed[0], "text": "Demo"}, {**parsed[0], "text": "2026"}]
assert ocr.conservative_candidates(words)[0]["text"] == "Demo 2026"
assert not ocr.conservative_candidates([words[0], {**words[1], "confidence": 70}])
assert len(ocr.conservative_candidates([*parsed, {**parsed[0], "line_id": ["2"]}])) == 1

for text in ["not tsv", header + word.replace("\t20\t20\t40\t20\t", "\t-1\t20\t40\t20\t"),
             header + word.replace("\t90\t", "\tnan\t")]:
    try:
        ocr.parse_tsv(text, 1, 100, 100)
        raise AssertionError("Malformed output was accepted")
    except ValueError:
        pass

print("PASS: CER, miss/extra counts, coordinate mapping, malformed-output checks")
