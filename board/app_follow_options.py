"""Shared, hardware-free command-line contract for the App bridge and worker."""
from __future__ import annotations

from dataclasses import dataclass
import math


@dataclass(frozen=True)
class FollowOptions:
    gestures: bool = False
    gesture_resume: str = "Closed_Fist"
    target_distance: float | None = None
    avoidance: str = "off"
    reverse_follow: bool = False
    reverse_distance: float | None = None

    @classmethod
    def from_namespace(cls, args):
        return cls(args.gestures, args.gesture_resume, args.target_distance,
                   args.avoidance, args.reverse_follow, args.reverse_distance)

    def command_args(self):
        result = ["--gestures" if self.gestures else "--no-gestures",
                  "--gesture-resume", self.gesture_resume,
                  "--avoidance", self.avoidance,
                  "--reverse-follow" if self.reverse_follow else "--no-reverse-follow"]
        if self.target_distance is not None:
            result.extend(("--target-distance", str(self.target_distance)))
        if self.reverse_distance is not None:
            result.extend(("--reverse-distance", str(self.reverse_distance)))
        return result

    def response(self):
        # These are requested overrides, not measured or resolved runtime values.
        return dict(targetDistanceM=self.target_distance, gesturesEnabled=self.gestures,
                    resumeGesture=self.gesture_resume, avoidance=self.avoidance,
                    reverseEnabled=self.reverse_follow, reverseDistanceM=self.reverse_distance)


def add_follow_arguments(parser):
    """Register only the options supported by both App entry points."""
    parser.add_argument("--camera", choices=("config", "astra-pro", "gemini335"), default="config")
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--check-config", action="store_true",
                        help="validate configuration without opening devices; combine with "
                             "--execute to check execution-specific restrictions")
    gestures = parser.add_mutually_exclusive_group()
    gestures.add_argument("--gestures", dest="gestures", action="store_true", default=False)
    gestures.add_argument("--no-gestures", dest="gestures", action="store_false")
    parser.add_argument("--gesture-resume", choices=("Closed_Fist", "Thumb_Up"), default="Closed_Fist")
    parser.add_argument("--target-distance", type=float)
    # Keep the established explanatory error for shadow instead of silently
    # treating it as the unrelated simulated-motor mode.
    parser.add_argument("--avoidance", choices=("off", "shadow", "on"), default="off")
    reverse = parser.add_mutually_exclusive_group()
    reverse.add_argument("--reverse-follow", dest="reverse_follow", action="store_true", default=False)
    reverse.add_argument("--no-reverse-follow", dest="reverse_follow", action="store_false")
    parser.add_argument("--reverse-distance", type=float)


def validate_follow_arguments(parser, args):
    """Reject unsupported combinations before creating any control session."""
    if args.avoidance == "shadow":
        parser.error("App follow does not support avoidance shadow; use "
                     "request_continuous_follow.py with --navigation-config")
    if args.dry_run and args.execute:
        parser.error("dry-run cannot execute motors")
    if args.check_config and args.dry_run:
        parser.error("check-config cannot use dry-run")
    for name, value in (("target-distance", args.target_distance),
                        ("reverse-distance", args.reverse_distance)):
        if value is not None and (not math.isfinite(value) or not .5 <= value <= 5.):
            parser.error(f"{name} must be a finite distance from 0.5 to 5 metres")
    if args.avoidance == "on" and args.camera != "astra-pro":
        parser.error("avoidance on requires camera astra-pro")
