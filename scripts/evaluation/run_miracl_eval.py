#!/usr/bin/env python3
"""上传 MIRACL PDF 子集、导入 cases 并执行检索矩阵。"""

from __future__ import annotations

import json
import os
import re
import secrets
import time
from pathlib import Path

import requests


BASE_URL = os.environ.get("RAG2AGENT_BASE_URL", "http://localhost:18080").rstrip("/")
ROOT = Path(__file__).resolve().parents[2]
PREPARED = Path(
    os.environ.get("RAG2AGENT_EVAL_PREPARED", ROOT / "eval-data/miracl/prepared")
).resolve()
STATE_PATH = PREPARED / "run-state.json"
KB_NAME = os.environ.get("RAG2AGENT_EVAL_KB_NAME", "MIRACL zh dev shard0 20260819")
RUN_PREFIX = os.environ.get("RAG2AGENT_EVAL_RUN_PREFIX", "MIRACL-zh-dev")
EVALUATE_GENERATION = os.environ.get("RAG2AGENT_EVALUATE_GENERATION", "false").lower() == "true"


def request_json(session: requests.Session, method: str, path: str, **kwargs):
    response = session.request(method, BASE_URL + path, timeout=120, **kwargs)
    response.raise_for_status()
    payload = response.json()
    if str(payload.get("code")) != "0":
        raise RuntimeError(f"{method} {path} failed: {payload}")
    return payload.get("data")


def login_or_register(session: requests.Session) -> str:
    credentials_path = PREPARED / "credentials.json"
    credentials = json.loads(credentials_path.read_text(encoding="utf-8")) if credentials_path.exists() else None
    if credentials is None:
        dataset_slug = re.sub(r"[^a-z0-9]+", "_", PREPARED.parent.name.lower()).strip("_")[:20]
        credentials = {
            "username": f"eval_{dataset_slug}_{time.strftime('%Y%m%d')}",
            "password": secrets.token_urlsafe(18)[:24],
            "nickname": "MIRACL evaluation",
        }
        response = session.post(BASE_URL + "/api/auth/register", json=credentials, timeout=60)
        if response.status_code >= 400:
            raise RuntimeError(f"register failed: {response.status_code} {response.text}")
        credentials_path.write_text(json.dumps(credentials, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    data = request_json(
        session,
        "POST",
        "/api/auth/login",
        json={"username": credentials["username"], "password": credentials["password"]},
    )
    return data["token"]


def load_state() -> dict:
    if STATE_PATH.exists():
        return json.loads(STATE_PATH.read_text(encoding="utf-8"))
    return {"uploaded": {}, "indexed": False, "casesImported": False, "runs": []}


def save_state(state: dict) -> None:
    STATE_PATH.write_text(json.dumps(state, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> None:
    files = json.loads((PREPARED / "documents.files.json").read_text(encoding="utf-8"))
    external_cases = json.loads((PREPARED / "cases.external.json").read_text(encoding="utf-8"))
    state = load_state()
    session = requests.Session()
    session.headers["Accept"] = "application/json"
    session.headers["satoken"] = login_or_register(session)

    kb_id = state.get("kbId")
    if kb_id is None:
        kb = request_json(
            session,
            "POST",
            "/api/knowledge-bases",
            json={
                "name": KB_NAME,
                "description": "Public evaluation dataset with fixed revision and local manifest",
            },
        )
        kb_id = kb["id"]
        state["kbId"] = kb_id
        save_state(state)

    def upload_item(item: dict) -> int:
        pdf_path = PREPARED / "pdfs" / item["fileName"]
        with pdf_path.open("rb") as document:
            data = request_json(
                session,
                "POST",
                "/api/documents/upload",
                params={"kbId": kb_id},
                files={"file": (pdf_path.name, document, "application/pdf")},
            )
        state["uploaded"][item["externalDocumentId"]] = data["id"]
        save_state(state)
        return data["id"]

    existing_documents = {
        document["id"]: document
        for document in request_json(session, "GET", "/api/documents", params={"kbId": kb_id})
    }
    for index, item in enumerate(files, start=1):
        external_id = item["externalDocumentId"]
        existing_id = state["uploaded"].get(external_id)
        if existing_id in existing_documents and existing_documents[existing_id]["status"] != "FAILED":
            continue
        upload_item(item)
        if index % 10 == 0 or index == len(files):
            print(f"uploaded {index}/{len(files)}")

    reuploaded: set[str] = set()
    deadline = time.time() + 1800
    while True:
        documents = request_json(session, "GET", "/api/documents", params={"kbId": kb_id})
        tracked_ids = set(state["uploaded"].values())
        tracked = [document for document in documents if document["id"] in tracked_ids]
        statuses = {document["status"] for document in tracked}
        indexed = sum(document["status"] == "INDEXED" for document in tracked)
        failed = [document for document in tracked if document["status"] == "FAILED"]
        print(f"indexing {indexed}/{len(files)}, statuses={sorted(statuses)}")
        if failed:
            by_id = {document_id: item for item in files for document_id in [state["uploaded"].get(item["externalDocumentId"])]}
            for document in failed:
                item = by_id.get(document["id"])
                if item is None or item["externalDocumentId"] in reuploaded:
                    raise RuntimeError(f"document indexing failed: {failed[:3]}")
                upload_item(item)
                reuploaded.add(item["externalDocumentId"])
            print(f"reuploaded {len(failed)} failed documents")
        if indexed >= len(files):
            state["indexed"] = True
            save_state(state)
            break
        if time.time() > deadline:
            raise TimeoutError("等待 MIRACL 文档入库超时")
        time.sleep(10)

    if not state.get("casesImported"):
        cases = [
            {
                "question": item["question"],
                "expectedAnswer": item["expectedAnswer"],
                "goldenDocumentIds": [state["uploaded"][external_id] for external_id in item["goldenExternalDocumentIds"]],
            }
            for item in external_cases
        ]
        request_json(session, "POST", "/api/evaluations/cases/import", json={"kbId": kb_id, "cases": cases})
        state["casesImported"] = True
        save_state(state)

    if not state["runs"]:
        configs = (
            [{"strategy": "VECTOR", "topK": 5, "candidateTopK": 20, "rrfK": 60, "rerankEnabled": True, "evaluateGeneration": True}]
            if EVALUATE_GENERATION
            else [
                {"strategy": "VECTOR", "topK": 5, "candidateTopK": 20, "rrfK": 60, "rerankEnabled": True, "evaluateGeneration": False},
                {"strategy": "KEYWORD", "topK": 5, "candidateTopK": 20, "rrfK": 60, "rerankEnabled": False, "evaluateGeneration": False},
                {"strategy": "AUTO", "topK": 5, "candidateTopK": 20, "rrfK": 60, "rerankEnabled": True, "evaluateGeneration": False},
            ]
        )
        for index, config in enumerate(configs, start=1):
            report = request_json(
                session,
                "POST",
                "/api/evaluations/runs",
                json={"kbId": kb_id, "name": f"{RUN_PREFIX}-{index}", "config": config},
            )
            state["runs"].append(report)
            save_state(state)
            print(json.dumps({"runId": report["runId"], "status": report["status"], "hitAtK": report["hitAtK"], "mrr": report["mrr"]}, ensure_ascii=False))

    summaries = [
        {
            "runId": report["runId"],
            "status": report["status"],
            "hitAtK": report["hitAtK"],
            "mrr": report["mrr"],
            "faithfulness": report["faithfulness"],
            "answerCorrectness": report["answerCorrectness"],
        }
        for report in state["runs"]
    ]
    print(json.dumps({"kbId": kb_id, "caseCount": len(external_cases), "runs": summaries}, ensure_ascii=False))


if __name__ == "__main__":
    main()
