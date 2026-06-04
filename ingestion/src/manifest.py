import uuid
import yaml


def load_manifest(manifest_path: str) -> dict:
    with open(manifest_path, "r") as f:
        return yaml.safe_load(f)


def doc_id_for(file_path: str) -> str:
    """Stable doc identifier — uuid5 of the normalised file path."""
    return str(uuid.uuid5(uuid.NAMESPACE_URL, file_path))
