from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

from .analyzer import init_engines, analyze_and_anonymize


@asynccontextmanager
async def lifespan(app: FastAPI):
    init_engines()
    yield


app = FastAPI(title="DLP Service", lifespan=lifespan)


class AnalyzeRequest(BaseModel):
    text: str
    allow_entities: list[str] = []


class AnalyzeResponse(BaseModel):
    cleaned_text: str
    entities_redacted: int


@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/dlp/analyze", response_model=AnalyzeResponse)
def analyze(request: AnalyzeRequest) -> AnalyzeResponse:
    if not request.text:
        raise HTTPException(status_code=400, detail="text field is required")
    cleaned, count = analyze_and_anonymize(request.text, request.allow_entities)
    return AnalyzeResponse(cleaned_text=cleaned, entities_redacted=count)
