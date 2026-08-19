#!/usr/bin/env python3
"""从 MIRACL 中文 dev 集生成项目可消费的本地检索评测子集。"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import random
import re
from collections import defaultdict
from pathlib import Path


DATASET_REVISION = "5be20db9509754dadad47689368639fcec739c00"
CORPUS_REVISION = "d921ec7e349ce0d28daf30b2da9da5ee698bef0d"
LICENSE = "Apache-2.0"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_topics(path: Path) -> dict[str, str]:
    topics: dict[str, str] = {}
    with path.open("r", encoding="utf-8") as source:
        for line in source:
            query_id, question = line.rstrip("\n").split("\t", 1)
            topics[query_id] = question
    return topics


def load_qrels(path: Path) -> tuple[dict[str, list[str]], dict[str, list[str]], set[str]]:
    positives: dict[str, list[str]] = defaultdict(list)
    negatives: dict[str, list[str]] = defaultdict(list)
    target_ids: set[str] = set()
    with path.open("r", encoding="utf-8") as source:
        for line in source:
            query_id, _, document_id, relevance = line.rstrip("\n").split("\t")
            target_ids.add(document_id)
            (positives if int(relevance) > 0 else negatives)[query_id].append(document_id)
    return positives, negatives, target_ids


def load_corpus_subset(
    path: Path, target_ids: set[str], random_distractor_count: int, seed: int
) -> tuple[dict[str, dict[str, str]], list[dict[str, str]]]:
    matched: dict[str, dict[str, str]] = {}
    random_distractors: list[dict[str, str]] = []
    rng = random.Random(seed)
    seen_random = 0
    with gzip.open(path, "rt", encoding="utf-8") as source:
        for line in source:
            document = json.loads(line)
            document_id = document["docid"]
            if document_id in target_ids:
                matched[document_id] = document
                continue
            seen_random += 1
            if len(random_distractors) < random_distractor_count:
                random_distractors.append(document)
            else:
                replacement = rng.randrange(seen_random)
                if replacement < random_distractor_count:
                    random_distractors[replacement] = document
    return matched, random_distractors


def prepare(args: argparse.Namespace) -> None:
    data_dir = args.data_dir.resolve()
    output_dir = args.output_dir.resolve()
    topics_path = data_dir / "topics.miracl-v1.0-zh-dev.tsv"
    qrels_path = data_dir / "qrels.miracl-v1.0-zh-dev.tsv"
    corpus_path = data_dir / "docs-0.jsonl.gz"
    for path in (topics_path, qrels_path, corpus_path):
        if not path.is_file():
            raise FileNotFoundError(path)

    topics = load_topics(topics_path)
    positives, negatives, target_ids = load_qrels(qrels_path)
    matched, random_distractors = load_corpus_subset(
        corpus_path, target_ids, args.random_distractors, args.seed
    )

    eligible_query_ids = [
        query_id
        for query_id in topics
        if any(document_id in matched for document_id in positives.get(query_id, []))
    ]
    selected_query_ids = eligible_query_ids[: args.case_count]
    if len(selected_query_ids) < args.case_count:
        raise RuntimeError(
            f"分片内只有 {len(selected_query_ids)} 条可映射用例，少于要求的 {args.case_count} 条"
        )

    selected_document_ids: set[str] = set()
    cases: list[dict[str, object]] = []
    for query_id in selected_query_ids:
        golden_ids = [document_id for document_id in positives[query_id] if document_id in matched]
        hard_negative_ids = [
            document_id
            for document_id in negatives.get(query_id, [])
            if document_id in matched
        ][: args.hard_negatives_per_case]
        selected_document_ids.update(golden_ids)
        selected_document_ids.update(hard_negative_ids)
        cases.append(
            {
                "queryId": query_id,
                "question": topics[query_id],
                "expectedAnswer": "",
                "goldenExternalDocumentIds": golden_ids,
            }
        )

    documents = [matched[document_id] for document_id in sorted(selected_document_ids)]
    existing_ids = {document["docid"] for document in documents}
    documents.extend(
        document for document in random_distractors if document["docid"] not in existing_ids
    )

    output_dir.mkdir(parents=True, exist_ok=True)
    with (output_dir / "documents.jsonl").open("w", encoding="utf-8", newline="\n") as target:
        for document in documents:
            target.write(json.dumps(document, ensure_ascii=False) + "\n")
    (output_dir / "cases.external.json").write_text(
        json.dumps(cases, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    manifest = {
        "dataset": "miracl/miracl zh dev",
        "license": LICENSE,
        "datasetRevision": DATASET_REVISION,
        "corpusRevision": CORPUS_REVISION,
        "caseCount": len(cases),
        "documentCount": len(documents),
        "seed": args.seed,
        "inputs": {
            path.name: {"sha256": sha256(path), "bytes": path.stat().st_size}
            for path in (topics_path, qrels_path, corpus_path)
        },
    }
    (output_dir / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


def create_pdfs(args: argparse.Namespace) -> None:
    try:
        from fontTools.ttLib import TTFont as FontToolsTTFont
        from reportlab.lib.enums import TA_LEFT
        from reportlab.lib.pagesizes import A4
        from reportlab.lib.styles import ParagraphStyle
        from reportlab.lib.units import mm
        from reportlab.pdfbase import pdfmetrics
        from reportlab.pdfbase.ttfonts import TTFont
        from reportlab.platypus import Paragraph, SimpleDocTemplate, Spacer
        from xml.sax.saxutils import escape
    except ImportError as error:
        raise RuntimeError("缺少 reportlab，请先安装评测工具依赖") from error

    input_path = args.output_dir.resolve() / "documents.jsonl"
    pdf_dir = args.output_dir.resolve() / "pdfs"
    pdf_dir.mkdir(parents=True, exist_ok=True)
    font_path = Path("C:/Windows/Fonts/NotoSansSC-VF.ttf")
    fallback_font_path = Path("C:/Windows/Fonts/seguisym.ttf")
    if not font_path.is_file():
        raise FileNotFoundError(f"缺少中文字体: {font_path}")
    if not fallback_font_path.is_file():
        raise FileNotFoundError(f"缺少符号字体: {fallback_font_path}")
    pdfmetrics.registerFont(TTFont("EvalChinese", str(font_path)))
    pdfmetrics.registerFont(TTFont("EvalFallback", str(fallback_font_path)))
    primary_cmap = FontToolsTTFont(font_path).getBestCmap()
    fallback_cmap = FontToolsTTFont(fallback_font_path).getBestCmap()

    def font_safe_markup(value: str) -> str:
        parts: list[str] = []
        fallback_run: list[str] = []

        def flush_fallback() -> None:
            if fallback_run:
                parts.append(f'<font name="EvalFallback">{escape("".join(fallback_run))}</font>')
                fallback_run.clear()

        for character in value.replace("\x00", ""):
            codepoint = ord(character)
            if codepoint in primary_cmap:
                flush_fallback()
                parts.append(escape(character))
            elif codepoint in fallback_cmap:
                fallback_run.append(character)
            else:
                flush_fallback()
                parts.append(f"[U+{codepoint:04X}]")
        flush_fallback()
        return "".join(parts)
    title_style = ParagraphStyle(
        "title", fontName="EvalChinese", fontSize=16, leading=22, alignment=TA_LEFT
    )
    body_style = ParagraphStyle(
        "body", fontName="EvalChinese", fontSize=11, leading=18, alignment=TA_LEFT
    )
    id_style = ParagraphStyle(
        "id", fontName="Helvetica", fontSize=8, leading=12, textColor="#666666"
    )
    mapping: list[dict[str, str]] = []
    with input_path.open("r", encoding="utf-8") as source:
        for line in source:
            document = json.loads(line)
            title = font_safe_markup(document["title"])
            body = font_safe_markup(document["text"])
            safe_id = re.sub(r"[^A-Za-z0-9._-]+", "_", document["docid"])
            file_name = f"{getattr(args, 'file_prefix', 'miracl-zh')}-{safe_id}.pdf"
            pdf_path = pdf_dir / file_name
            story = [
                Paragraph(title, title_style),
                Spacer(1, 5 * mm),
                Paragraph(body, body_style),
                Spacer(1, 8 * mm),
                Paragraph(f"MIRACL external document id: {document['docid']}", id_style),
            ]
            SimpleDocTemplate(
                str(pdf_path),
                pagesize=A4,
                leftMargin=22 * mm,
                rightMargin=22 * mm,
                topMargin=20 * mm,
                bottomMargin=20 * mm,
                title=document["title"].replace("\x00", ""),
                author="MIRACL",
            ).build(story)
            mapping.append({"externalDocumentId": document["docid"], "fileName": file_name})
    (args.output_dir.resolve() / "documents.files.json").write_text(
        json.dumps(mapping, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps({"pdfCount": len(mapping), "pdfDirectory": str(pdf_dir)}, ensure_ascii=False))


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--data-dir", type=Path, default=Path("eval-data/miracl"))
    parser.add_argument("--output-dir", type=Path, default=Path("eval-data/miracl/prepared"))
    parser.add_argument("--case-count", type=int, default=100)
    parser.add_argument("--hard-negatives-per-case", type=int, default=1)
    parser.add_argument("--random-distractors", type=int, default=100)
    parser.add_argument("--seed", type=int, default=20260819)
    parser.add_argument("--create-pdfs", action="store_true")
    return parser.parse_args()


if __name__ == "__main__":
    arguments = parse_args()
    prepare(arguments)
    if arguments.create_pdfs:
        create_pdfs(arguments)
