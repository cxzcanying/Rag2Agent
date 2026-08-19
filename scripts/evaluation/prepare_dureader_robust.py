#!/usr/bin/env python3
"""准备带人工答案的 DuReader Robust 生成质量评测子集。"""

from __future__ import annotations

import argparse
import hashlib
import json
import random
from pathlib import Path

from prepare_miracl_zh import create_pdfs


REVISION = "10003b9e4d5e0afaff73fcf366f02d49880ec17a"
LICENSE = "Apache-2.0"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--data-dir", type=Path, default=Path("eval-data/dureader-robust"))
    parser.add_argument("--output-dir", type=Path, default=Path("eval-data/dureader-robust/prepared"))
    parser.add_argument("--case-count", type=int, default=30)
    parser.add_argument("--distractor-count", type=int, default=30)
    parser.add_argument("--seed", type=int, default=20260819)
    parser.add_argument("--create-pdfs", action="store_true")
    args = parser.parse_args()

    archive_path = args.data_dir.resolve() / "dureader_robust-data.tar.gz"
    dev_path = args.data_dir.resolve() / "dureader_robust-data/dev.json"
    data = json.loads(dev_path.read_text(encoding="utf-8"))
    candidates: list[dict[str, object]] = []
    for index, paragraph in enumerate(data["data"][0]["paragraphs"]):
        for qa in paragraph["qas"]:
            answers = [answer["text"] for answer in qa.get("answers", []) if answer.get("text")]
            if answers and answers[0] in paragraph["context"]:
                candidates.append(
                    {
                        "docid": f"dureader-dev-{index}",
                        "title": data["data"][0].get("title") or "DuReader Robust",
                        "text": paragraph["context"],
                        "question": qa["question"],
                        "answer": answers[0],
                    }
                )

    rng = random.Random(args.seed)
    rng.shuffle(candidates)
    selected = candidates[: args.case_count]
    distractors = candidates[args.case_count : args.case_count + args.distractor_count]
    if len(selected) < args.case_count or len(distractors) < args.distractor_count:
        raise RuntimeError("DuReader Robust 可用数据不足")

    documents = [
        {"docid": item["docid"], "title": item["title"], "text": item["text"]}
        for item in selected + distractors
    ]
    cases = [
        {
            "queryId": item["docid"],
            "question": item["question"],
            "expectedAnswer": item["answer"],
            "goldenExternalDocumentIds": [item["docid"]],
        }
        for item in selected
    ]

    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    with (output_dir / "documents.jsonl").open("w", encoding="utf-8", newline="\n") as target:
        for document in documents:
            target.write(json.dumps(document, ensure_ascii=False) + "\n")
    (output_dir / "cases.external.json").write_text(
        json.dumps(cases, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    manifest = {
        "dataset": "PaddlePaddle/dureader_robust dev",
        "license": LICENSE,
        "revision": REVISION,
        "caseCount": len(cases),
        "documentCount": len(documents),
        "seed": args.seed,
        "input": {"file": archive_path.name, "sha256": sha256(archive_path), "bytes": archive_path.stat().st_size},
    }
    (output_dir / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps(manifest, ensure_ascii=False, indent=2))
    if args.create_pdfs:
        args.file_prefix = "dureader-robust"
        create_pdfs(args)


if __name__ == "__main__":
    main()
