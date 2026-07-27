"""
Generate golden parity vectors for the Java gradient-boosting engine.

Kangaroo re-implements LightGBM scoring in pure Java so that the runtime needs no native library
and no interpreter. "Re-implements" is a claim, and this script is what turns it into a checkable
one: it scores a large set of feature vectors with the reference LightGBM implementation and writes
the inputs and outputs to a file that `GbmParityTest` replays through the Java engine, failing the
build if any probability differs by more than 1e-9.

The vectors are drawn to stress the parts of the tree walk that are easy to get wrong rather than
to look like a realistic patient mix:

  * exact split boundaries, and both sides of them by one ULP
  * the -1 "missing" sentinel on every continuous feature
  * all-zero and all-one flag rows
  * values outside the range the model ever saw in training

Run:  python tools/generate_parity_vectors.py
Needs: lightgbm, numpy   (development only -- nothing here ships in the product)
"""

import json
import random
from pathlib import Path

import lightgbm as lgb
import numpy as np

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
MODELS = ROOT / "src" / "main" / "resources" / "models"
OUT = ROOT / "testdata"

SEED = 20260726
N_RANDOM = 4000


def thresholds_of(model_file):
    """Every threshold that appears anywhere in the model, per feature index."""
    per_feature = {}
    current_split, current_thresh = None, None
    for line in model_file.read_text(encoding="utf-8").splitlines():
        if line.startswith("split_feature="):
            current_split = [int(x) for x in line[len("split_feature="):].split()]
        elif line.startswith("threshold="):
            current_thresh = [float(x) for x in line[len("threshold="):].split()]
            if current_split is not None and len(current_split) == len(current_thresh):
                for f, t in zip(current_split, current_thresh):
                    per_feature.setdefault(f, set()).add(t)
            current_split, current_thresh = None, None
    return {f: sorted(v) for f, v in per_feature.items()}


def build_rows(n_features, bounds, thresholds, rng):
    """Random rows plus deliberately adversarial ones."""
    rows = []

    # 1. Uniform random inside the training range.
    for _ in range(N_RANDOM):
        row = []
        for f in range(n_features):
            lo, hi = bounds[f]
            row.append(rng.uniform(lo, hi))
        rows.append(row)

    # 2. Flags all off, all on; continuous features at their extremes and at the missing sentinel.
    for fill in (0.0, 1.0, -1.0):
        rows.append([fill] * n_features)
    rows.append([bounds[f][0] for f in range(n_features)])
    rows.append([bounds[f][1] for f in range(n_features)])

    # 3. Sit exactly on every threshold, and one ULP either side of it. LightGBM's split test is
    #    `value <= threshold`, so an off-by-one-ULP port fails precisely here and nowhere else.
    for f, ts in thresholds.items():
        for t in ts:
            for value in (t, np.nextafter(t, -np.inf), np.nextafter(t, np.inf)):
                row = [0.0] * n_features
                row[f] = float(value)
                rows.append(row)

    # 4. Random rows whose values are drawn only from the set of real thresholds, so many splits
    #    land on their boundary at once.
    all_t = {f: list(ts) for f, ts in thresholds.items()}
    for _ in range(N_RANDOM // 2):
        row = []
        for f in range(n_features):
            if f in all_t and all_t[f]:
                row.append(float(rng.choice(all_t[f])))
            else:
                lo, hi = bounds[f]
                row.append(rng.uniform(lo, hi))
        rows.append(row)

    return rows


def emit(name, model_path, meta_path, bounds_fn):
    booster = lgb.Booster(model_file=str(model_path))
    meta = json.loads(meta_path.read_text(encoding="utf-8"))
    best_iteration = meta["best_iteration"]

    n_features = booster.num_feature()
    feature_names = booster.feature_name()
    rng = random.Random(SEED)
    np.random.seed(SEED)

    bounds = bounds_fn(n_features, feature_names)
    thresholds = thresholds_of(model_path)
    rows = build_rows(n_features, bounds, thresholds, rng)

    x = np.asarray(rows, dtype=np.float32)
    # num_iteration is the whole point: the file holds every tree ever built, including the ones
    # after early stopping decided they were making things worse.
    probs = booster.predict(x, num_iteration=best_iteration)

    out = OUT / f"gbm_parity_{name}.tsv"
    OUT.mkdir(parents=True, exist_ok=True)
    with out.open("w", encoding="utf-8", newline="\n") as fh:
        fh.write(f"# {name} parity vectors\n")
        fh.write(f"# model={model_path.name} best_iteration={best_iteration} "
                 f"n_features={n_features} n_classes={probs.shape[1]}\n")
        fh.write(f"# lightgbm={lgb.__version__}\n")
        fh.write("# columns: <n_features float32 inputs> TAB <n_classes float64 probabilities>\n")
        for xi, pi in zip(x, probs):
            fh.write("\t".join(repr(float(v)) for v in xi))
            fh.write("\t")
            fh.write("\t".join(repr(float(v)) for v in pi))
            fh.write("\n")

    print(f"{name}: {len(rows)} vectors, {n_features} features, {probs.shape[1]} classes -> {out}")


def clinical_bounds(n_features, names):
    """Flags are 0/1; the four continuous features get their real ranges plus the -1 sentinel."""
    wide = {
        "age_days": (-1.0, 40.0),
        "weight_kg": (-1.0, 6.0),
        "resp_rate": (-1.0, 90.0),
        "jaundice_extent": (0.0, 4.0),
    }
    return [wide.get(names[f], (0.0, 1.0)) for f in range(n_features)]


def jaundice_bounds(n_features, names):
    """Colour features live on very different scales; bound each by what it can physically be."""
    def bound(name):
        if name.startswith("wb_gain"):
            return (0.3, 3.0)
        if name.endswith("_frac"):
            return (0.0, 1.0)
        if name.startswith("skin_") or name in ("chroma_mean", "chroma_std"):
            return (0.0, 255.0)
        if name in ("rb_diff", "gb_diff", "rg_diff", "yellow_idx"):
            return (-120.0, 120.0)
        if name.endswith("_over_b"):
            return (0.2, 5.0)
        if name == "lab_L":
            return (0.0, 100.0)
        if name in ("lab_a", "lab_b"):
            return (-100.0, 100.0)
        if name == "hsv_h":
            return (0.0, 360.0)
        if name in ("hsv_s", "hsv_v"):
            return (0.0, 1.0)
        return (-50.0, 255.0)
    return [bound(names[f]) for f in range(n_features)]


if __name__ == "__main__":
    emit("clinical", MODELS / "clinical_gbm.txt", MODELS / "clinical_gbm_meta.json", clinical_bounds)
    emit("jaundice", MODELS / "jaundice_gbm.txt", MODELS / "jaundice_gbm_meta.json", jaundice_bounds)
