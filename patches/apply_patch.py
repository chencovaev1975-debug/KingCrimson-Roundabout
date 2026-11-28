from pathlib import Path

root = Path(".")
renderer = root / "common/src/main/java/net/hydra/jojomod/mixin/ZLevelRenderer.java"


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    source = path.read_text(encoding="utf-8")
    count = source.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(source.replace(old, new, 1), encoding="utf-8")


# The ONLY gameplay-visual change: once Roundabout's native Time Erase transition
# (dark-magenta fog + floating terrain scraps + skybox fade-in) has fully finished,
# stop drawing world chunks so only the native cosmic skybox remains.
#
# The transition itself is untouched. The chunks are hidden only while:
#   - Time Erase is actively being used, AND
#   - the native fade-in has reached its end (TimeErase >= fadeTime).
# As soon as the ability ends, chunks are drawn again and the native fade-out plays.
replace_once(
    renderer,
    """        if (ClientUtil.isUnderRTZ() && ClientUtil.rtzTicks >= 4 && ClientUtil.rtzTicks < ClientUtil.RTZ_DURATION - 4) {
            ci.cancel();
        }
    }""",
    """        if (ClientUtil.isUnderRTZ() && ClientUtil.rtzTicks >= 4 && ClientUtil.rtzTicks < ClientUtil.RTZ_DURATION - 4) {
            ci.cancel();
        }
        if (ClientUtil.isUsingTimeErase && ClientUtil.TimeErase >= ClientUtil.fadeTime) {
            ci.cancel();
        }
    }""",
    "hide world chunks after the native Time Erase transition",
)

print("Applied: native Time Erase transition kept; world chunks hidden after it, leaving the native cosmic skybox.")
