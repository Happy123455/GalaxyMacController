#!/usr/bin/env python3
"""
Galaxy Watch to Mac Air Controller - macOS Companion Server & Neural Network Studio
Listens for packets from the Galaxy Watch (Wear OS) over UDP (Wi-Fi) or TCP (ADB tunnel)
and drives the cursor, clicks, hold/drag and scrolling through native CoreGraphics events.
Includes an optional Web Dashboard & PyTorch 1D-CNN Waveform Pattern Studio on http://localhost:8766.
"""

import sys
import os
import json
import time
import socket
import argparse
import threading
import socketserver
import ctypes
from ctypes import c_double, c_uint32, c_int32, c_int64, Structure, c_void_p
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler

# numpy / torch only power the optional Neural Studio dashboard. The controller
# itself runs without them.
try:
    import numpy as np
    import torch
    import torch.nn as nn
    import torch.optim as optim
    has_torch = True
except ImportError:
    has_torch = False

# ==========================================
# macOS CoreGraphics CTypes Bindings
# ==========================================
class CGPoint(Structure):
    _fields_ = [('x', c_double), ('y', c_double)]

class CGSize(Structure):
    _fields_ = [('width', c_double), ('height', c_double)]

class CGRect(Structure):
    _fields_ = [('origin', CGPoint), ('size', CGSize)]

try:
    cg = ctypes.cdll.LoadLibrary('/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics')
    app_services = ctypes.cdll.LoadLibrary('/System/Library/Frameworks/ApplicationServices.framework/ApplicationServices')
except Exception as e:
    print(f"[Error] Failed to load macOS CoreGraphics framework: {e}")
    sys.exit(1)

kCGEventLeftMouseDown = 1
kCGEventLeftMouseUp = 2
kCGEventRightMouseDown = 3
kCGEventRightMouseUp = 4
kCGEventMouseMoved = 5
kCGEventLeftMouseDragged = 6
kCGEventRightMouseDragged = 7
kCGEventScrollWheel = 22

kCGMouseButtonLeft = 0
kCGMouseButtonRight = 1
kCGMouseButtonCenter = 2

kCGHIDEventTap = 0
kCGScrollEventUnitPixel = 0

# CGEventField ids
kCGMouseEventClickState = 1
kCGMouseEventDeltaX = 4
kCGMouseEventDeltaY = 5

cg.CGEventCreate.restype = c_void_p
cg.CGEventCreate.argtypes = [c_void_p]

cg.CGEventGetLocation.restype = CGPoint
cg.CGEventGetLocation.argtypes = [c_void_p]

cg.CGEventCreateMouseEvent.restype = c_void_p
cg.CGEventCreateMouseEvent.argtypes = [c_void_p, c_uint32, CGPoint, c_uint32]

# Non-variadic variant (the classic CGEventCreateScrollWheelEvent is variadic and
# breaks under ctypes on Apple Silicon).
cg.CGEventCreateScrollWheelEvent2.restype = c_void_p
cg.CGEventCreateScrollWheelEvent2.argtypes = [c_void_p, c_uint32, c_uint32, c_int32, c_int32, c_int32]

cg.CGEventSetIntegerValueField.restype = None
cg.CGEventSetIntegerValueField.argtypes = [c_void_p, c_uint32, c_int64]

cg.CGEventPost.restype = None
cg.CGEventPost.argtypes = [c_uint32, c_void_p]

cg.CFRelease.restype = None
cg.CFRelease.argtypes = [c_void_p]

cg.CGMainDisplayID.restype = c_uint32
cg.CGGetActiveDisplayList.restype = c_int32
cg.CGGetActiveDisplayList.argtypes = [c_uint32, ctypes.POINTER(c_uint32), ctypes.POINTER(c_uint32)]
cg.CGDisplayBounds.restype = CGRect
cg.CGDisplayBounds.argtypes = [c_uint32]

try:
    app_services.AXIsProcessTrusted.restype = ctypes.c_bool
    app_services.AXIsProcessTrusted.argtypes = []
    has_ax_func = True
except AttributeError:
    has_ax_func = False

try:
    core_foundation = ctypes.cdll.LoadLibrary('/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation')
    CFStringCreateWithCString = core_foundation.CFStringCreateWithCString
    CFStringCreateWithCString.restype = c_void_p
    CFStringCreateWithCString.argtypes = [c_void_p, ctypes.c_char_p, c_int32]

    CFRelease = core_foundation.CFRelease
    CFRelease.restype = None
    CFRelease.argtypes = [c_void_p]

    AXUIElementCreateSystemWide = app_services.AXUIElementCreateSystemWide
    AXUIElementCreateSystemWide.restype = c_void_p

    AXUIElementCopyElementAtPosition = app_services.AXUIElementCopyElementAtPosition
    AXUIElementCopyElementAtPosition.restype = c_int32
    AXUIElementCopyElementAtPosition.argtypes = [c_void_p, ctypes.c_float, ctypes.c_float, ctypes.POINTER(c_void_p)]

    AXUIElementCopyAttributeValue = app_services.AXUIElementCopyAttributeValue
    AXUIElementCopyAttributeValue.restype = c_int32
    AXUIElementCopyAttributeValue.argtypes = [c_void_p, c_void_p, ctypes.POINTER(c_void_p)]

    AXValueGetValue = app_services.AXValueGetValue
    AXValueGetValue.restype = ctypes.c_bool
    AXValueGetValue.argtypes = [c_void_p, c_int32, c_void_p]

    kCFStringEncodingUTF8 = 0x08000100
    str_ax_pos = CFStringCreateWithCString(None, b"AXPosition", kCFStringEncodingUTF8)
    str_ax_size = CFStringCreateWithCString(None, b"AXSize", kCFStringEncodingUTF8)
    has_ax_snapping = True
except Exception:
    has_ax_snapping = False


class MacMouseController:
    """Native cursor / click / scroll injection. All public methods are thread-safe."""

    DOUBLE_CLICK_INTERVAL = 0.45  # seconds, close to the macOS default
    DOUBLE_CLICK_RADIUS = 6.0     # px
    RESYNC_IDLE_S = 0.12          # re-read the real cursor after this much idle time

    def __init__(self, magnetic_snap=False, dwell_click=False, dwell_time=1.0, scroll_scale=1.5):
        self.lock = threading.RLock()
        self.magnetic_snap_enabled = magnetic_snap and has_ax_snapping
        self.dwell_click_enabled = dwell_click
        self.dwell_time = dwell_time
        self.scroll_scale = scroll_scale

        self._refresh_display_bounds()
        pos = self.get_cursor_position()
        self.current_x = pos.x
        self.current_y = pos.y
        self.last_move_time = 0.0

        self.left_down = False
        self.right_down = False
        self.is_dragging = False

        self.last_click_time = 0.0
        self.last_click_pos = (0.0, 0.0)
        self.last_click_button = None
        self.click_count = 0

        self.scroll_rem_x = 0.0
        self.scroll_rem_y = 0.0

        # Magnetic snapping (opt-in): AX lookups are rate limited and only run
        # when the cursor is nearly still, so they never add pointer latency.
        self.sys_elem = AXUIElementCreateSystemWide() if has_ax_snapping else None
        self.last_snap_query = 0.0
        self.snap_target = None

        # Dwell click (opt-in)
        self.dwell_anchor_x = self.current_x
        self.dwell_anchor_y = self.current_y
        self.dwell_start_time = time.time()
        self.has_dwell_clicked = True  # never fire before the first real movement

    # ---------- geometry ----------
    def _refresh_display_bounds(self):
        """Union of all active displays so the cursor can travel across monitors."""
        ids = (c_uint32 * 16)()
        count = c_uint32(0)
        rects = []
        if cg.CGGetActiveDisplayList(16, ids, ctypes.byref(count)) == 0:
            rects = [cg.CGDisplayBounds(ids[i]) for i in range(count.value)]
        if not rects:
            rects = [cg.CGDisplayBounds(cg.CGMainDisplayID())]
        self.min_x = min(r.origin.x for r in rects)
        self.min_y = min(r.origin.y for r in rects)
        self.max_x = max(r.origin.x + r.size.width for r in rects) - 1.0
        self.max_y = max(r.origin.y + r.size.height for r in rects) - 1.0
        self.display_rects = rects
        self.bounds_refreshed_at = time.time()

    def _clamp_to_displays(self, x, y):
        """Clamp to the display containing (x, y), or to the nearest one."""
        best = None
        best_d = None
        for r in self.display_rects:
            cx = min(max(x, r.origin.x), r.origin.x + r.size.width - 1.0)
            cy = min(max(y, r.origin.y), r.origin.y + r.size.height - 1.0)
            d = (cx - x) ** 2 + (cy - y) ** 2
            if best_d is None or d < best_d:
                best, best_d = (cx, cy), d
                if d == 0:
                    break
        return best

    def get_cursor_position(self):
        ev = cg.CGEventCreate(None)
        if not ev:
            return CGPoint(self.current_x if hasattr(self, "current_x") else 0.0,
                           self.current_y if hasattr(self, "current_y") else 0.0)
        loc = cg.CGEventGetLocation(ev)
        cg.CFRelease(ev)
        return loc

    def _resync_if_idle(self, now):
        # If the trackpad moved the cursor while the watch was idle, continue from there.
        if now - self.last_move_time > self.RESYNC_IDLE_S:
            pos = self.get_cursor_position()
            self.current_x, self.current_y = pos.x, pos.y
        if now - self.bounds_refreshed_at > 5.0:
            self._refresh_display_bounds()

    # ---------- event helpers ----------
    def _post_mouse(self, event_type, button, x, y, click_state=None, dx=0, dy=0):
        ev = cg.CGEventCreateMouseEvent(None, event_type, CGPoint(x, y), button)
        if not ev:
            return
        if click_state is not None:
            cg.CGEventSetIntegerValueField(ev, kCGMouseEventClickState, click_state)
        if dx or dy:
            cg.CGEventSetIntegerValueField(ev, kCGMouseEventDeltaX, int(round(dx)))
            cg.CGEventSetIntegerValueField(ev, kCGMouseEventDeltaY, int(round(dy)))
        cg.CGEventPost(kCGHIDEventTap, ev)
        cg.CFRelease(ev)

    # ---------- magnetic snap ----------
    def _query_button_center(self, x, y):
        elem = c_void_p()
        res = AXUIElementCopyElementAtPosition(self.sys_elem, x, y, ctypes.byref(elem))
        if res != 0 or not elem.value:
            return None
        pos_val = c_void_p()
        size_val = c_void_p()
        btn_pos = CGPoint()
        btn_size = CGSize()
        has_pos = AXUIElementCopyAttributeValue(elem, str_ax_pos, ctypes.byref(pos_val)) == 0 and pos_val.value
        has_sz = AXUIElementCopyAttributeValue(elem, str_ax_size, ctypes.byref(size_val)) == 0 and size_val.value
        if has_pos:
            AXValueGetValue(pos_val, 1, ctypes.byref(btn_pos))
            CFRelease(pos_val)
        if has_sz:
            AXValueGetValue(size_val, 2, ctypes.byref(btn_size))
            CFRelease(size_val)
        CFRelease(elem)
        if has_pos and has_sz and 10 <= btn_size.width <= 200 and 10 <= btn_size.height <= 80:
            return (btn_pos.x + btn_size.width / 2.0, btn_pos.y + btn_size.height / 2.0)
        return None

    def _apply_magnetic_snap(self, x, y, speed, now):
        if speed > 3.0:
            self.snap_target = None
            return x, y
        if now - self.last_snap_query > 0.12:
            self.last_snap_query = now
            self.snap_target = self._query_button_center(x, y)
        if self.snap_target:
            cx, cy = self.snap_target
            if ((x - cx) ** 2 + (y - cy) ** 2) ** 0.5 < 24.0:
                # Gentle pull, proportional to how still the hand is.
                pull = 0.12 * (1.0 - speed / 3.0)
                return x + (cx - x) * pull, y + (cy - y) * pull
        return x, y

    # ---------- dwell ----------
    def _check_dwell_click(self, x, y, now):
        drift = abs(x - self.dwell_anchor_x) + abs(y - self.dwell_anchor_y)
        if drift < 5.0:
            if not self.has_dwell_clicked and (now - self.dwell_start_time) >= self.dwell_time:
                print(f"🎯 [Dwell Click] {self.dwell_time:.1f}s hover -> LEFT CLICK")
                self.click("left")
                self.has_dwell_clicked = True
        else:
            self.dwell_anchor_x, self.dwell_anchor_y = x, y
            self.dwell_start_time = now
            self.has_dwell_clicked = False

    # ---------- public API ----------
    def move_by(self, dx, dy):
        with self.lock:
            now = time.time()
            self._resync_if_idle(now)
            self.last_move_time = now

            x, y = self._clamp_to_displays(self.current_x + dx, self.current_y + dy)
            if self.magnetic_snap_enabled and not self.is_dragging:
                x, y = self._apply_magnetic_snap(x, y, abs(dx) + abs(dy), now)

            self.current_x, self.current_y = x, y

            if self.left_down:
                self._post_mouse(kCGEventLeftMouseDragged, kCGMouseButtonLeft, x, y, dx=dx, dy=dy)
            elif self.right_down:
                self._post_mouse(kCGEventRightMouseDragged, kCGMouseButtonRight, x, y, dx=dx, dy=dy)
            else:
                self._post_mouse(kCGEventMouseMoved, kCGMouseButtonLeft, x, y, dx=dx, dy=dy)

            if self.dwell_click_enabled and not self.is_dragging:
                self._check_dwell_click(x, y, now)

    def _next_click_state(self, button, x, y, now):
        lx, ly = self.last_click_pos
        if (button == self.last_click_button
                and now - self.last_click_time <= self.DOUBLE_CLICK_INTERVAL
                and abs(x - lx) <= self.DOUBLE_CLICK_RADIUS and abs(y - ly) <= self.DOUBLE_CLICK_RADIUS):
            self.click_count += 1
        else:
            self.click_count = 1
        self.last_click_time = now
        self.last_click_pos = (x, y)
        self.last_click_button = button
        return self.click_count

    def click(self, button="left", count=1):
        """Click at the current cursor position. count=2 sends a true double-click."""
        with self.lock:
            now = time.time()
            self._resync_if_idle(now)
            x, y = self.current_x, self.current_y
            if button == "right":
                down, up, btn = kCGEventRightMouseDown, kCGEventRightMouseUp, kCGMouseButtonRight
            else:
                down, up, btn = kCGEventLeftMouseDown, kCGEventLeftMouseUp, kCGMouseButtonLeft
            for _ in range(max(1, count)):
                state = self._next_click_state(button, x, y, time.time())
                self._post_mouse(down, btn, x, y, click_state=state)
                time.sleep(0.012)
                self._post_mouse(up, btn, x, y, click_state=state)
                if count > 1:
                    time.sleep(0.02)
            self.has_dwell_clicked = True

    def left_click(self):
        self.click("left")

    def right_click(self):
        self.click("right")

    def mouse_down(self, button="left"):
        with self.lock:
            now = time.time()
            self._resync_if_idle(now)
            x, y = self.current_x, self.current_y
            if button == "right":
                if not self.right_down:
                    self.right_down = True
                    self._post_mouse(kCGEventRightMouseDown, kCGMouseButtonRight, x, y, click_state=1)
            else:
                if not self.left_down:
                    self.left_down = True
                    self.is_dragging = True
                    self._post_mouse(kCGEventLeftMouseDown, kCGMouseButtonLeft, x, y, click_state=1)

    def mouse_up(self, button="left"):
        with self.lock:
            x, y = self.current_x, self.current_y
            if button == "right":
                if self.right_down:
                    self.right_down = False
                    self._post_mouse(kCGEventRightMouseUp, kCGMouseButtonRight, x, y, click_state=1)
            else:
                if self.left_down:
                    self.left_down = False
                    self.is_dragging = False
                    self._post_mouse(kCGEventLeftMouseUp, kCGMouseButtonLeft, x, y, click_state=1)

    def release_all(self):
        self.mouse_up("left")
        self.mouse_up("right")

    def scroll(self, scroll_x, scroll_y):
        """Smooth pixel scrolling. Positive scroll_y scrolls up, positive scroll_x scrolls left."""
        with self.lock:
            self.scroll_rem_y += scroll_y * self.scroll_scale
            self.scroll_rem_x += scroll_x * self.scroll_scale
            wy = int(self.scroll_rem_y)
            wx = int(self.scroll_rem_x)
            if wy == 0 and wx == 0:
                return
            self.scroll_rem_y -= wy
            self.scroll_rem_x -= wx
            ev = cg.CGEventCreateScrollWheelEvent2(None, kCGScrollEventUnitPixel, 2, wy, wx, 0)
            if ev:
                cg.CGEventPost(kCGHIDEventTap, ev)
                cg.CFRelease(ev)

# ==========================================
# PyTorch 1D-CNN Waveform Pattern Classifier
# ==========================================
class Pinch1DCNN(nn.Module if has_torch else object):
    def __init__(self):
        super().__init__()
        self.conv = nn.Sequential(
            nn.Conv1d(1, 16, kernel_size=5, padding=2),
            nn.ReLU(),
            nn.MaxPool1d(2),
            nn.Conv1d(16, 32, kernel_size=3, padding=1),
            nn.ReLU(),
            nn.AdaptiveAvgPool1d(1)
        )
        self.fc = nn.Sequential(
            nn.Linear(32, 16),
            nn.ReLU(),
            nn.Linear(16, 3) # Class 0: Single Pinch, Class 1: Double Pinch, Class 2: Triple Pinch
        )

    def forward(self, x):
        feat = self.conv(x).squeeze(-1)
        return self.fc(feat)

# ==========================================
# Shared Studio State & Dataset
# ==========================================
class StudioState:
    def __init__(self):
        self.model = Pinch1DCNN() if has_torch else None
        self.is_trained = False
        self.dataset = []
        self.latest_event = None
        if has_torch:
            self.seed_initial_archetypes()

    def seed_initial_archetypes(self):
        # Generate canonical Gaussian waveform archetypes for visualization
        t = np.linspace(0, 600, 60) # 600ms window

        # 1. Single Pinch Archetype: sharp peak at t=200ms
        for _ in range(5):
            noise = np.random.normal(0, 0.1, 60)
            peak_amp = np.random.uniform(3.8, 4.6)
            s = peak_amp * np.exp(-((t - 200) ** 2) / (2 * (25 ** 2))) + noise
            self.dataset.append({"gesture": "Single Pinch", "label": 0, "samples": np.clip(s, 0, 8).tolist(), "peak": peak_amp, "interval": 0})

        # 2. Double Pinch Archetype: peaks at t=160ms and t=370ms (interval = 210ms)
        for _ in range(5):
            noise = np.random.normal(0, 0.1, 60)
            peak1 = np.random.uniform(3.7, 4.5)
            peak2 = np.random.uniform(3.5, 4.4)
            s = peak1 * np.exp(-((t - 160) ** 2) / (2 * (24 ** 2))) + peak2 * np.exp(-((t - 370) ** 2) / (2 * (24 ** 2))) + noise
            self.dataset.append({"gesture": "Double Pinch", "label": 1, "samples": np.clip(s, 0, 8).tolist(), "peak": max(peak1, peak2), "interval": 210})

        # 3. Triple Pinch Archetype: peaks at t=130ms, t=310ms, t=490ms (intervals ~180ms)
        for _ in range(5):
            noise = np.random.normal(0, 0.1, 60)
            p1 = np.random.uniform(3.6, 4.3)
            p2 = np.random.uniform(3.5, 4.2)
            p3 = np.random.uniform(3.6, 4.4)
            s = (p1 * np.exp(-((t - 130) ** 2) / (2 * (22 ** 2))) +
                 p2 * np.exp(-((t - 310) ** 2) / (2 * (22 ** 2))) +
                 p3 * np.exp(-((t - 490) ** 2) / (2 * (22 ** 2)))) + noise
            self.dataset.append({"gesture": "Triple Pinch", "label": 2, "samples": np.clip(s, 0, 8).tolist(), "peak": max(p1, p2, p3), "interval": 180})

        self.train_model()

    def train_model(self):
        if not has_torch:
            return {"status": "error", "message": "Install numpy + torch to enable the ML studio"}
        if len(self.dataset) < 6:
            return {"status": "error", "message": "Need at least 6 samples"}

        X = torch.tensor([d["samples"] for d in self.dataset], dtype=torch.float32).unsqueeze(1)
        y = torch.tensor([d["label"] for d in self.dataset], dtype=torch.long)

        optimizer = optim.Adam(self.model.parameters(), lr=0.01)
        criterion = nn.CrossEntropyLoss()

        self.model.train()
        for epoch in range(45):
            optimizer.zero_grad()
            out = self.model(X)
            loss = criterion(out, y)
            loss.backward()
            optimizer.step()

        self.model.eval()
        with torch.no_grad():
            preds = self.model(X).argmax(dim=1)
            acc = (preds == y).float().mean().item() * 100.0

        self.is_trained = True
        return {"status": "ok", "accuracy": acc, "samples": len(self.dataset), "loss": round(loss.item(), 4)}

    def predict(self, samples):
        if not has_torch or len(samples) < 8:
            return {}
        self.model.eval()
        with torch.no_grad():
            x = torch.tensor(samples, dtype=torch.float32).unsqueeze(0).unsqueeze(0)
            logits = self.model(x)
            probs = torch.softmax(logits, dim=1).squeeze(0).numpy()
            pred_idx = int(np.argmax(probs))
            return {
                "pred_label": pred_idx,
                "pred_name": ["Single Pinch", "Double Pinch", "Triple Pinch"][pred_idx],
                "confidence": round(float(probs[pred_idx]) * 100.0, 1),
                "probs": [round(float(p) * 100.0, 1) for p in probs]
            }

studio_state = StudioState()

# ==========================================
# Web Dashboard HTTP Server (Port 8766)
# ==========================================
DASHBOARD_HTML = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Galaxy Watch Mac Controller - Neural Gesture Studio</title>
<style>
  :root {
    --bg: #0d1117;
    --card-bg: #161b22;
    --border: #30363d;
    --cyan: #00e5ff;
    --purple: #7c4dff;
    --gold: #ffb74d;
    --green: #00e676;
    --text: #c9d1d9;
  }
  body {
    margin: 0;
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
    background: var(--bg);
    color: var(--text);
    padding: 24px;
  }
  header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    border-bottom: 1px solid var(--border);
    padding-bottom: 16px;
    margin-bottom: 24px;
  }
  h1 { margin: 0; font-size: 20px; color: #fff; display: flex; align-items: center; gap: 8px; }
  .badge { background: #238636; color: #fff; padding: 4px 10px; border-radius: 12px; font-size: 11px; font-weight: bold; }
  .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; }
  .card {
    background: var(--card-bg);
    border: 1px solid var(--border);
    border-radius: 12px;
    padding: 18px;
  }
  h2 { margin-top: 0; font-size: 15px; color: #fff; display: flex; align-items: center; gap: 8px; }
  canvas { width: 100%; height: 200px; background: #0a0d14; border-radius: 8px; border: 1px solid #21262d; }
  .legend { display: flex; gap: 16px; font-size: 11px; margin-top: 8px; justify-content: center; }
  .legend-item { display: flex; align-items: center; gap: 6px; }
  .dot { width: 10px; height: 10px; border-radius: 50%; }
  .btn-group { display: flex; gap: 8px; margin-top: 12px; }
  button {
    background: #21262d;
    border: 1px solid var(--border);
    color: #fff;
    padding: 8px 14px;
    border-radius: 6px;
    font-size: 12px;
    font-weight: 600;
    cursor: pointer;
    transition: all 0.15s ease;
  }
  button:hover { background: #30363d; border-color: #8b949e; }
  button.primary { background: #1f6feb; border-color: #388bfd; }
  button.primary:hover { background: #388bfd; }
  button.success { background: #238636; border-color: #2ea043; }
  .status-box { background: #0a0d14; border-radius: 8px; padding: 12px; font-family: monospace; font-size: 12px; line-height: 1.6; }
  .archetype-tabs { display: flex; gap: 8px; margin-bottom: 12px; }
  .tab-btn { background: transparent; border: 1px solid var(--border); color: #8b949e; padding: 5px 12px; border-radius: 16px; font-size: 11px; }
  .tab-btn.active { background: #21262d; color: #fff; border-color: var(--cyan); }
</style>
</head>
<body>

<header>
  <h1>⌚ Galaxy Watch 8 ➔ Mac Neural Studio</h1>
  <div style="display:flex; align-items:center; gap:12px;">
    <span id="modelStatus" class="badge">PyTorch 1D-CNN: Ready</span>
    <span style="font-size:12px; color:#8b949e;">Local Server: 127.0.0.1:8765</span>
  </div>
</header>

<div class="grid">
  <!-- Left Column: Overlapping Pattern Corridors -->
  <div class="card">
    <h2>📈 Waveform Pattern Showcase (Ensemble Overlap)</h2>
    <p style="font-size:12px; color:#8b949e; margin-top:-4px;">
      Overlapping historical waveforms show the distinct clusters for 1, 2, and 3 pinches.
    </p>
    <canvas id="overlapCanvas"></canvas>
    <div class="legend">
      <div class="legend-item"><div class="dot" style="background:var(--cyan)"></div> Single Pinch (Ignored)</div>
      <div class="legend-item"><div class="dot" style="background:var(--purple)"></div> Double Pinch (Left Click)</div>
      <div class="legend-item"><div class="dot" style="background:var(--gold)"></div> Triple Pinch (Right Click)</div>
    </div>
    <div style="margin-top:14px; font-size:12px; color:#8b949e;">
      • <strong>Single:</strong> 1 needle pulse (&lt;70ms) followed by flat baseline.<br>
      • <strong>Double:</strong> 2 sharp peaks separated by a ~210ms valley.<br>
      • <strong>Triple:</strong> 3 rhythmic peaks separated by harmonic valleys.
    </div>
  </div>

  <!-- Right Column: Live Strike Inspector & Review -->
  <div class="card">
    <h2>🎯 Live Click Review & Neural Network Inspector</h2>
    <p style="font-size:12px; color:#8b949e; margin-top:-4px;">
      As you pinch on your watch while using your Mac, the latest waveform appears here for inspection.
    </p>
    <canvas id="liveCanvas"></canvas>
    
    <div class="status-box" id="strikeDetails" style="margin-top:12px;">
      Waiting for gesture from watch...
    </div>

    <div style="margin-top:14px;">
      <span style="font-size:12px; font-weight:600;">Human-in-the-Loop Review:</span>
      <div class="btn-group">
        <button class="primary" onclick="submitReview('Single Pinch', 0)">✓ Label Single (1)</button>
        <button style="background:#4a148c; border-color:#7c4dff;" onclick="submitReview('Double Pinch', 1)">✓ Label Double (2)</button>
        <button style="background:#e65100; border-color:#ff9100;" onclick="submitReview('Triple Pinch', 2)">✓ Label Triple (3)</button>
        <button onclick="discardStrike()">✗ Discard</button>
      </div>
    </div>

    <div style="margin-top:16px; border-top:1px solid var(--border); padding-top:12px; display:flex; justify-content:space-between; align-items:center;">
      <span id="datasetCount" style="font-size:12px; color:#8b949e;">Dataset: 15 samples</span>
      <button class="success" onclick="trainNeuralNet()">🧠 Train PyTorch 1D-CNN</button>
    </div>
  </div>
</div>

<script>
  let latestEvent = null;
  let dataset = [];

  function drawOverlapGraph() {
    const canvas = document.getElementById('overlapCanvas');
    const ctx = canvas.getContext('2d');
    canvas.width = canvas.parentElement.clientWidth - 40;
    canvas.height = 200;
    const w = canvas.width;
    const h = canvas.height;

    ctx.clearRect(0, 0, w, h);

    // Draw grid
    ctx.strokeStyle = '#21262d';
    ctx.lineWidth = 1;
    for (let y = 0; y < h; y += 40) {
      ctx.beginPath(); ctx.moveTo(0, y); ctx.lineTo(w, y); ctx.stroke();
    }

    // Colors per label
    const colors = ['rgba(0, 229, 255, 0.35)', 'rgba(124, 77, 255, 0.35)', 'rgba(255, 183, 77, 0.35)'];

    dataset.forEach(item => {
      const pts = item.samples;
      const col = colors[item.label] || 'rgba(255,255,255,0.2)';
      ctx.strokeStyle = col;
      ctx.lineWidth = 2;
      ctx.beginPath();
      pts.forEach((val, idx) => {
        const x = (idx / (pts.length - 1)) * w;
        const y = h - ((val / 8.0) * h);
        if (idx === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
      });
      ctx.stroke();
    });

    // If there's a live candidate, draw it bold white on top!
    if (latestEvent && latestEvent.samples) {
      const pts = latestEvent.samples;
      ctx.strokeStyle = '#ffffff';
      ctx.lineWidth = 3.5;
      ctx.shadowColor = '#00e5ff';
      ctx.shadowBlur = 10;
      ctx.beginPath();
      pts.forEach((val, idx) => {
        const x = (idx / (pts.length - 1)) * w;
        const y = h - ((val / 8.0) * h);
        if (idx === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
      });
      ctx.stroke();
      ctx.shadowBlur = 0;
    }
  }

  function drawLiveGraph(samples) {
    const canvas = document.getElementById('liveCanvas');
    const ctx = canvas.getContext('2d');
    canvas.width = canvas.parentElement.clientWidth - 40;
    canvas.height = 140;
    const w = canvas.width;
    const h = canvas.height;

    ctx.clearRect(0, 0, w, h);
    if (!samples || samples.length === 0) return;

    ctx.strokeStyle = '#00e5ff';
    ctx.lineWidth = 3;
    ctx.beginPath();
    samples.forEach((val, idx) => {
      const x = (idx / (samples.length - 1)) * w;
      const y = h - ((val / 8.0) * h);
      if (idx === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
    });
    ctx.stroke();
  }

  async function pollState() {
    try {
      const res = await fetch('/api/state');
      const data = await res.json();
      dataset = data.dataset || [];
      document.getElementById('datasetCount').innerText = `Dataset: ${dataset.length} labeled waveforms`;
      
      if (data.latest_event && (!latestEvent || data.latest_event.timestamp !== latestEvent.timestamp)) {
        latestEvent = data.latest_event;
        drawLiveGraph(latestEvent.samples);
        const pred = data.latest_prediction || {};
        document.getElementById('strikeDetails').innerHTML = `
          <strong>Detected:</strong> <span style="color:#00e5ff;">${latestEvent.gesture}</span> (${latestEvent.count} tap${latestEvent.count>1?'s':''})<br>
          <strong>Peak Force:</strong> ${latestEvent.peak.toFixed(1)} m/s² | <strong>Interval:</strong> ${latestEvent.interval}ms<br>
          <strong>Neural Net Confidence:</strong> <span style="color:#00e676;">${pred.confidence != null ? pred.confidence + '%' : 'n/a'}</span> (Predicted: ${pred.pred_name || latestEvent.gesture})
        `;
      }
      drawOverlapGraph();
    } catch (e) {
      console.error(e);
    }
  }

  async function submitReview(gestureName, labelIdx) {
    if (!latestEvent) return;
    await fetch('/api/review', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({
        gesture: gestureName,
        label: labelIdx,
        samples: latestEvent.samples,
        peak: latestEvent.peak,
        interval: latestEvent.interval
      })
    });
    pollState();
  }

  function discardStrike() {
    latestEvent = null;
    document.getElementById('strikeDetails').innerText = 'Strike discarded.';
    drawLiveGraph([]);
    drawOverlapGraph();
  }

  async function trainNeuralNet() {
    document.getElementById('modelStatus').innerText = 'Training PyTorch Model...';
    const res = await fetch('/api/train', { method: 'POST' });
    const data = await res.json();
    if (data.status === 'ok') {
      document.getElementById('modelStatus').innerText = `PyTorch Accuracy: ${data.accuracy.toFixed(1)}% (Loss: ${data.loss})`;
      alert(`🎉 PyTorch 1D-CNN successfully trained on ${data.samples} samples! Accuracy: ${data.accuracy.toFixed(1)}%`);
    } else {
      alert(data.message);
    }
  }

  setInterval(pollState, 800);
  window.addEventListener('resize', () => { drawOverlapGraph(); if (latestEvent) drawLiveGraph(latestEvent.samples); });
  pollState();
</script>

</body>
</html>
"""

class StudioHTTPHandler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        pass # Silence standard HTTP logs to keep terminal clean

    def do_GET(self):
        if self.path == "/" or self.path == "/index.html":
            self.send_response(200)
            self.send_header("Content-Type", "text/html")
            self.end_headers()
            self.wfile.write(DASHBOARD_HTML.encode("utf-8"))
        elif self.path == "/api/state":
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            latest = studio_state.latest_event
            pred = studio_state.predict(latest["samples"]) if (latest and "samples" in latest) else {}
            payload = {
                "dataset": studio_state.dataset,
                "latest_event": latest,
                "latest_prediction": pred,
                "is_trained": studio_state.is_trained
            }
            self.wfile.write(json.dumps(payload).encode("utf-8"))
        else:
            self.send_response(404)
            self.end_headers()

    def do_POST(self):
        if self.path == "/api/review":
            content_length = int(self.headers.get('Content-Length', 0))
            body = self.rfile.read(content_length)
            data = json.loads(body.decode('utf-8'))
            studio_state.dataset.append(data)
            print(f"✅ [Mac Studio] Labeled & added new {data['gesture']} sample to Neural Dataset (Total: {len(studio_state.dataset)})")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({"status": "ok"}).encode("utf-8"))
        elif self.path == "/api/train":
            res = studio_state.train_model()
            print(f"🧠 [PyTorch] Model trained on {res.get('samples', 0)} samples! Accuracy: {res.get('accuracy', 0):.1f}%")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps(res).encode("utf-8"))
        else:
            self.send_response(404)
            self.end_headers()

def run_http_server(port=8766):
    # Bound to localhost only: the dashboard is for this Mac, not the whole LAN.
    server = ThreadingHTTPServer(("127.0.0.1", port), StudioHTTPHandler)
    server.serve_forever()

# ==========================================
# Packet Router (shared by UDP + TCP transports)
# ==========================================
class PacketRouter:
    CLIENT_TIMEOUT_S = 4.0  # release held buttons if the watch goes silent

    def __init__(self, controller, verbose=False):
        self.controller = controller
        self.verbose = verbose
        self.hostname = socket.gethostname()
        self.local_ip = get_local_ip()
        self.connected_client = None
        self.last_packet_time = 0.0
        self.move_count = 0
        self.stats_since = time.time()

    def handle(self, raw, addr, reply, transport):
        try:
            msg = json.loads(raw.decode("utf-8") if isinstance(raw, bytes) else raw)
        except (ValueError, UnicodeDecodeError):
            return
        try:
            self._dispatch(msg, addr, reply, transport)
        except Exception as e:
            print(f"[Warning] Error handling packet: {e}")

    def _dispatch(self, msg, addr, reply, transport):
        msg_type = msg.get("type", "")
        self.last_packet_time = time.time()

        if msg_type in ("discover", "ping"):
            client = (transport, addr[0])
            if self.connected_client != client:
                self.connected_client = client
                device = msg.get("device", "Galaxy Watch")
                print(f"✨ [CONNECTED] {device} via {transport} from {addr[0]}")
            reply({
                "type": "mac_ack" if msg_type == "discover" else "pong",
                "server_name": self.hostname,
                "server_ip": self.local_ip,
                "ts": msg.get("ts", 0),
            })

        elif msg_type == "move":
            self.controller.move_by(float(msg.get("dx", 0.0)), float(msg.get("dy", 0.0)))
            if self.verbose:
                self.move_count += 1
                now = time.time()
                if now - self.stats_since >= 5.0:
                    print(f"📈 {self.move_count / (now - self.stats_since):.0f} move packets/s")
                    self.move_count = 0
                    self.stats_since = now

        elif msg_type == "click":
            button = msg.get("button", "left")
            action = msg.get("action", "click")
            if action == "click":
                print(f"🤏 [Gesture] {button.upper()} CLICK")
                self.controller.click(button)
            elif action == "double":
                print(f"🤏 [Gesture] {button.upper()} DOUBLE CLICK")
                self.controller.click(button, count=2)
            elif action == "down":
                print("✊ [Gesture] HOLD / DRAG START")
                self.controller.mouse_down(button)
            elif action == "up":
                print("✋ [Gesture] HOLD / DRAG END")
                self.controller.mouse_up(button)

        elif msg_type == "scroll":
            self.controller.scroll(float(msg.get("scrollX", 0.0)), float(msg.get("scrollY", 0.0)))

        elif msg_type == "gesture_review":
            gesture = msg.get("gesture", "Unknown")
            count = msg.get("count", 1)
            peak = float(msg.get("peak", 0.0))
            interval = int(msg.get("interval", 0))
            bars = "█" * min(int(peak * 4), 25)
            print(f"📊 [REVIEW] {gesture} ({count} tap{'s' if count > 1 else ''}) | Peak: {peak:.1f} m/s² [{bars:<25}] | Interval: {interval}ms")

        elif msg_type == "waveform_event":
            studio_state.latest_event = msg
            samples = msg.get("samples", [])
            if samples and has_torch and self.verbose:
                pred = studio_state.predict(samples)
                print(f"🧠 [Waveform] {msg.get('gesture', '')} | PyTorch: {pred['pred_name']} ({pred['confidence']}%)")

    def watchdog(self):
        """Never leave a mouse button stuck down if the watch disappears mid-drag."""
        while True:
            time.sleep(1.0)
            c = self.controller
            if (c.left_down or c.right_down) and time.time() - self.last_packet_time > self.CLIENT_TIMEOUT_S:
                print("⚠️  Watch went silent during a drag -> releasing mouse buttons")
                c.release_all()


def get_local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


def make_tcp_server(port, router):
    """TCP listener for the ADB tunnel (`adb reverse tcp:8765 tcp:8765`). Newline-delimited JSON."""

    class Handler(socketserver.StreamRequestHandler):
        def handle(self):
            self.connection.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            write_lock = threading.Lock()

            def reply(obj):
                data = (json.dumps(obj) + "\n").encode("utf-8")
                with write_lock:
                    try:
                        self.wfile.write(data)
                        self.wfile.flush()
                    except OSError:
                        pass

            addr = self.client_address
            for line in self.rfile:
                line = line.strip()
                if line:
                    router.handle(line, addr, reply, "ADB tunnel")
            print("🔌 ADB tunnel client disconnected")
            router.connected_client = None
            router.controller.release_all()

    class Server(socketserver.ThreadingTCPServer):
        allow_reuse_address = True
        daemon_threads = True

    return Server(("127.0.0.1", port), Handler)


def check_accessibility():
    if has_ax_func and not app_services.AXIsProcessTrusted():
        print("⚠️  ACCESSIBILITY PERMISSION MISSING — clicks and cursor movement will be ignored by macOS!")
        print("   System Settings > Privacy & Security > Accessibility -> enable the app running this")
        print(f"   server (Terminal / iTerm / {sys.executable}), then restart the server.\n")


# ==========================================
# Main
# ==========================================
def run_server(args):
    controller = MacMouseController(
        magnetic_snap=args.snap,
        dwell_click=args.dwell,
        dwell_time=args.dwell_time,
        scroll_scale=args.scroll_scale,
    )
    router = PacketRouter(controller, verbose=args.verbose)

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1 << 20)
    sock.bind(("0.0.0.0", args.port))

    print("================================================================")
    print(" 🚀 Galaxy Watch Air Controller - macOS Server")
    print("================================================================")
    print(f"  • UDP (Wi-Fi) port     : {args.port}")
    print(f"  • TCP (ADB tunnel) port: {args.port} on 127.0.0.1")
    print(f"  • Local Mac IP         : {router.local_ip}   <- enter this on the watch if discovery fails")
    print(f"  • Magnetic snap        : {'ON' if controller.magnetic_snap_enabled else 'off'}")
    print(f"  • Dwell click          : {'ON (%.1fs)' % args.dwell_time if args.dwell else 'off'}")
    if not args.no_dashboard:
        print(f"  • Dashboard            : http://localhost:8766{'' if has_torch else '  (install numpy+torch for the ML studio)'}")
    print("================================================================")
    check_accessibility()
    print("Waiting for the Galaxy Watch...\n")

    if not args.no_dashboard:
        threading.Thread(target=run_http_server, args=(8766,), daemon=True).start()

    try:
        tcp_server = make_tcp_server(args.port, router)
        threading.Thread(target=tcp_server.serve_forever, daemon=True).start()
    except OSError as e:
        print(f"[Warn] ADB tunnel listener unavailable: {e}")

    threading.Thread(target=router.watchdog, daemon=True).start()

    def udp_reply_to(addr):
        return lambda obj: sock.sendto(json.dumps(obj).encode("utf-8"), addr)

    try:
        while True:
            data, addr = sock.recvfrom(65535)
            router.handle(data, addr, udp_reply_to(addr), "Wi-Fi")
    except KeyboardInterrupt:
        print("\n👋 Server stopped by user.")
    finally:
        controller.release_all()
        sock.close()


def parse_args(argv):
    p = argparse.ArgumentParser(description="Galaxy Watch -> Mac air mouse server")
    p.add_argument("port", nargs="?", type=int, default=8765, help="UDP/TCP port (default 8765)")
    p.add_argument("--snap", action="store_true", help="enable magnetic snapping to buttons (Accessibility API)")
    p.add_argument("--dwell", action="store_true", help="enable hover-to-click")
    p.add_argument("--dwell-time", type=float, default=1.0, help="hover duration for --dwell (seconds)")
    p.add_argument("--scroll-scale", type=float, default=1.5, help="scroll speed multiplier")
    p.add_argument("--no-dashboard", action="store_true", help="don't start the web dashboard")
    p.add_argument("-v", "--verbose", action="store_true", help="print packet-rate stats and ML predictions")
    return p.parse_args(argv)


if __name__ == "__main__":
    run_server(parse_args(sys.argv[1:]))
