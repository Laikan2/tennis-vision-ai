import pytest


@pytest.fixture(autouse=True)
def isolate_trained_appearance(tmp_path,monkeypatch):
    # Unit fixtures exercise the colour fallback independently of a user's
    # installed weights. Learned-model integration is tested with explicit data.
    monkeypatch.setenv('TV_APPEARANCE_DIR',str(tmp_path/'appearance'))
