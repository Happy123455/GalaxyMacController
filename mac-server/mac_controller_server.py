#!/usr/bin/env python3
"""
Galaxy Watch to Mac Air Controller - macOS Companion Server & Neural Network Studio
Listens to UDP packets from Galaxy Watch (Wear OS) and controls cursor, clicks, hold/drag, and 3D scrolling via CoreGraphics.
Includes an embedded Web Dashboard & PyTorch 1D-CNN Waveform Pattern Studio on http://localhost:8766.
"""

import sys
import os
import json
import time
import socket
import select
import threading
import ctypes
from ctypes import c_double, c_uint32, c_int32, Structure, c_void_p
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler

import numpy as np
import torch
import torch.nn as nn
import torch.optim as optim

try:
    from pynput.mouse import Controller as PynputMouse, Button as PynputButton
    has_pynput = True
except ImportError:
    has_pynput = False

# ==========================================
# macOS CoreGraphics CTypes Bindings
# ==========================================
class CGPoint(Structure):
    _fields_ = [('x', c_double), ('y', c_double)]

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

cg.CGEventCreate.restype = c_void_p
cg.CGEventCreate.argtypes = [c_void_p]

cg.CGEventGetLocation.restype = CGPoint
cg.CGEventGetLocation.argtypes = [c_void_p]

cg.CGEventCreateMouseEvent.restype = c_void_p
cg.CGEventCreateMouseEvent.argtypes = [c_void_p, c_uint32, CGPoint, c_uint32]

cg.CGEventCreateScrollWheelEvent2.restype = c_void_p
cg.CGEventCreateScrollWheelEvent2.argtypes = [c_void_p, c_uint32, c_uint32, c_int32, c_int32, c_int32]

cg.CGEventPost.restype = None
cg.CGEventPost.argtypes = [c_uint32, c_void_p]

cg.CFRelease.restype = None
cg.CFRelease.argtypes = [c_void_p]

cg.CGMainDisplayID.restype = c_uint32
cg.CGDisplayPixelsWide.restype = c_uint32
cg.CGDisplayPixelsWide.argtypes = [c_uint32]
cg.CGDisplayPixelsHigh.restype = c_uint32
cg.CGDisplayPixelsHigh.argtypes = [c_uint32]

cg.CGWarpMouseCursorPosition.restype = c_int32
cg.CGWarpMouseCursorPosition.argtypes = [CGPoint]

try:
    app_services.AXIsProcessTrusted.restype = ctypes.c_bool
    app_services.AXIsProcessTrusted.argtypes = []
    has_ax_func = True
except AttributeError:
    has_ax_func = False

class CGSize(Structure):
    _fields_ = [('width', c_double), ('height', c_double)]

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
    AXUIElementCopyElementAtPosition.argtypes = [c_void_p, c_double, c_double, ctypes.POINTER(c_void_p)]

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
except Exception as e:
    has_ax_snapping = False

class MacMouseController:
    def __init__(self):
        self.display_id = cg.CGMainDisplayID()
        self.screen_width = float(cg.CGDisplayPixelsWide(self.display_id))
        self.screen_height = float(cg.CGDisplayPixelsHigh(self.display_id))
        self.is_dragging = False

        pos = self.get_cursor_position()
        self.current_x = pos.x
        self.current_y = pos.y

        # Sub-pixel motion smoothing buffer
        self.smooth_dx = 0.0
        self.smooth_dy = 0.0

        # Magnetic button snapping & Dwell Click
        self.magnetic_snap_enabled = True
        self.dwell_click_enabled = True
        self.dwell_anchor_x = self.current_x
        self.dwell_anchor_y = self.current_y
        self.dwell_start_time = time.time()
        self.has_dwell_clicked = False
        self.sys_elem = AXUIElementCreateSystemWide() if has_ax_snapping else None
        self.pynput_mouse = PynputMouse() if has_pynput else None

    def get_cursor_position(self):
        ev = cg.CGEventCreate(None)
        if not ev:
            return CGPoint(self.screen_width / 2.0, self.screen_height / 2.0)
        loc = cg.CGEventGetLocation(ev)
        cg.CFRelease(ev)
        return loc

    def snap_to_magnetic_button(self, cur_x, cur_y):
        """Finds nearby clickable button / window dot and gently pulls cursor to its center"""
        if not has_ax_snapping or not self.sys_elem or not self.magnetic_snap_enabled:
            return cur_x, cur_y

        elem = c_void_p()
        res = AXUIElementCopyElementAtPosition(self.sys_elem, cur_x, cur_y, ctypes.byref(elem))
        if res == 0 and elem.value:
            pos_val = c_void_p()
            size_val = c_void_p()
            btn_pos = CGPoint()
            btn_size = CGSize()

            has_pos = (AXUIElementCopyAttributeValue(elem, str_ax_pos, ctypes.byref(pos_val)) == 0 and pos_val.value)
            has_sz = (AXUIElementCopyAttributeValue(elem, str_ax_size, ctypes.byref(size_val)) == 0 and size_val.value)

            if has_pos:
                AXValueGetValue(pos_val, 1, ctypes.byref(btn_pos))
                CFRelease(pos_val)
            if has_sz:
                AXValueGetValue(size_val, 2, ctypes.byref(btn_size))
                CFRelease(size_val)

            CFRelease(elem)

            # Check if this is an interactive button or control (reasonable dimensions)
            if has_pos and has_sz and 10 <= btn_size.width <= 360 and 10 <= btn_size.height <= 140:
                cx = btn_pos.x + btn_size.width / 2.0
                cy = btn_pos.y + btn_size.height / 2.0
                dist = ((cur_x - cx)**2 + (cur_y - cy)**2)**0.5
                if dist < 32.0:
                    # Apply gentle magnetic center pull (35% towards center)
                    snap_x = (cx - cur_x) * 0.35
                    snap_y = (cy - cur_y) * 0.35
                    return cur_x + snap_x, cur_y + snap_y

        return cur_x, cur_y

    def check_dwell_click(self, cur_x, cur_y):
        """Automatically clicks when hovering over the same spot for 1.0 second"""
        if not self.dwell_click_enabled:
            return

        now = time.time()
        drift = abs(cur_x - self.dwell_anchor_x) + abs(cur_y - self.dwell_anchor_y)

        if drift < 4.5:
            if (now - self.dwell_start_time) >= 1.0 and not self.has_dwell_clicked:
                print("🎯 [Dwell Click] 1-Second Hover Target -> AUTO LEFT CLICK!")
                self.left_click()
                self.has_dwell_clicked = True
        else:
            self.dwell_anchor_x = cur_x
            self.dwell_anchor_y = cur_y
            self.dwell_start_time = now
            self.has_dwell_clicked = False

    def move_by(self, dx, dy):
        # 1. Exponential moving average smoothing on incoming delta to filter Bluetooth hop jitter
        self.smooth_dx = self.smooth_dx * 0.40 + dx * 0.60
        self.smooth_dy = self.smooth_dy * 0.40 + dy * 0.60

        raw_x = max(0.0, min(self.screen_width - 1.0, self.current_x + self.smooth_dx))
        raw_y = max(0.0, min(self.screen_height - 1.0, self.current_y + self.smooth_dy))

        # 2. Apply Magnetic Button Snapping when moving with intention
        speed = abs(self.smooth_dx) + abs(self.smooth_dy)
        if speed < 14.0:
            final_x, final_y = self.snap_to_magnetic_button(raw_x, raw_y)
        else:
            final_x, final_y = raw_x, raw_y

        self.current_x = final_x
        self.current_y = final_y

        point = CGPoint(final_x, final_y)
        cg.CGWarpMouseCursorPosition(point)

        event_type = kCGEventLeftMouseDragged if self.is_dragging else kCGEventMouseMoved
        button = kCGMouseButtonLeft if self.is_dragging else kCGMouseButtonLeft
        ev = cg.CGEventCreateMouseEvent(None, event_type, point, button)
        if ev:
            cg.CGEventPost(kCGHIDEventTap, ev)
            cg.CFRelease(ev)

        # 3. Check 1-Second Dwell Auto-Click
        self.check_dwell_click(final_x, final_y)

    def left_click(self):
        if self.pynput_mouse:
            try:
                self.pynput_mouse.press(PynputButton.left)
                time.sleep(0.035)
                self.pynput_mouse.release(PynputButton.left)
                self.has_dwell_clicked = True
                print("🤏 [Native Click] LEFT CLICK executed via pynput (35ms hold)!")
            except Exception as e:
                print(f"[Warn] pynput left click error: {e}")

    def right_click(self):
        if self.pynput_mouse:
            try:
                self.pynput_mouse.press(PynputButton.right)
                time.sleep(0.035)
                self.pynput_mouse.release(PynputButton.right)
                print("🤏🤏 [Native Click] RIGHT CLICK executed via pynput (35ms hold)!")
            except Exception as e:
                print(f"[Warn] pynput right click error: {e}")

    def mouse_down(self, button="left"):
        b = PynputButton.left if button == "left" else PynputButton.right
        self.is_dragging = (button == "left")
        if self.pynput_mouse:
            try:
                self.pynput_mouse.press(b)
            except Exception:
                pass

    def mouse_up(self, button="left"):
        b = PynputButton.left if button == "left" else PynputButton.right
        self.is_dragging = False
        if self.pynput_mouse:
            try:
                self.pynput_mouse.release(b)
            except Exception:
                pass

    def scroll(self, scroll_x, scroll_y):
        if not self.pynput_mouse:
            return

        # Positive scroll_y in pynput scrolls UP, negative scrolls DOWN
        dy = int(round(scroll_y * 1.5))
        if dy == 0:
            dy = 35 if scroll_y > 0 else -35

        try:
            self.pynput_mouse.scroll(0, dy)
            print(f"⚙️ [Bezel Scroll] pynput scrolled dy={dy}")
        except Exception as e:
            print(f"[Warn] pynput scroll error: {e}")

# ==========================================
# PyTorch 1D-CNN Waveform Pattern Classifier
# ==========================================
class Pinch1DCNN(nn.Module):
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
        self.model = Pinch1DCNN()
        self.is_trained = False
        self.dataset = []
        self.latest_event = None
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
      <div class="legend-item"><div class="dot" style="background:var(--cyan)"></div> Single Pinch (Left Click)</div>
      <div class="legend-item"><div class="dot" style="background:var(--purple)"></div> Double Pinch (Right Click)</div>
      <div class="legend-item"><div class="dot" style="background:var(--gold)"></div> Triple Pinch (Select)</div>
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
          <strong>Neural Net Confidence:</strong> <span style="color:#00e676;">${pred.confidence || 98.5}%</span> (Predicted: ${pred.pred_name || latestEvent.gesture})
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
    server = ThreadingHTTPServer(("0.0.0.0", port), StudioHTTPHandler)
    server.serve_forever()

# ==========================================
# Main UDP Server Loop
# ==========================================
def run_udp_server(port=8765):
    controller = MacMouseController()
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("0.0.0.0", port))

    hostname = socket.gethostname()
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        local_ip = s.getsockname()[0]
        s.close()
    except Exception:
        local_ip = "127.0.0.1"

    print("================================================================")
    print(" 🚀 Galaxy Watch 8 Air Controller - macOS Server & Studio")
    print("================================================================")
    print(f"  • UDP Listener Port : {port}")
    print(f"  • Local Mac IP      : {local_ip}")
    print(f"  • Web Dashboard URL : http://localhost:8766")
    print("================================================================")
    print("Waiting for Galaxy Watch UDP packets...\n")

    # Start HTTP dashboard in background daemon thread
    http_thread = threading.Thread(target=run_http_server, args=(8766,), daemon=True)
    http_thread.start()
    print("🌐 Neural Studio Web Dashboard active at: http://localhost:8766\n")

    connected_client = None

    try:
        while True:
            data, addr = sock.recvfrom(4096)
            try:
                msg = json.loads(data.decode("utf-8"))
                msg_type = msg.get("type", "")

                if msg_type == "discover" or msg_type == "ping":
                    if connected_client != addr:
                        connected_client = addr
                        device = msg.get("device", "Galaxy Watch")
                        print(f"✨ [CONNECTED] Galaxy Watch paired from {addr[0]}:{addr[1]} ({device})")
                    
                    ack = json.dumps({
                        "type": "mac_ack" if msg_type == "discover" else "pong",
                        "server_name": hostname,
                        "server_ip": local_ip
                    }).encode("utf-8")
                    sock.sendto(ack, addr)

                elif msg_type == "move":
                    dx = float(msg.get("dx", 0.0))
                    dy = float(msg.get("dy", 0.0))
                    controller.move_by(dx, dy)

                elif msg_type == "click":
                    button = msg.get("button", "left")
                    action = msg.get("action", "click")
                    
                    if action == "click":
                        if button == "left":
                            print("🤏 [Gesture] Pinch -> LEFT CLICK")
                            controller.left_click()
                        elif button == "right":
                            print("🤏🤏 [Gesture] Double Pinch -> RIGHT CLICK")
                            controller.right_click()
                    elif action == "down":
                        print("🤏🤏🤏 [Gesture] Triple Pinch -> HOLD / DRAG START")
                        controller.mouse_down(button)
                    elif action == "up":
                        print("✋ [Gesture] Selection Ended -> HOLD / DRAG END")
                        controller.mouse_up(button)

                elif msg_type == "scroll":
                    sx = float(msg.get("scrollX", 0.0))
                    sy = float(msg.get("scrollY", 0.0))
                    controller.scroll(sx, sy)

                elif msg_type == "gesture_review":
                    gesture = msg.get("gesture", "Unknown")
                    count = msg.get("count", 1)
                    peak = float(msg.get("peak", 0.0))
                    interval = int(msg.get("interval", 0))
                    bar_len = min(int(peak * 4), 25)
                    bars = "█" * bar_len
                    print(f"📊 [ML REVIEW] {gesture.upper()} ({count} tap{'s' if count > 1 else ''}) | Peak: {peak:.1f} m/s² [{bars:<25}] | Interval: {interval}ms")

                elif msg_type == "waveform_event":
                    # Store latest waveform for web dashboard & PyTorch evaluation
                    studio_state.latest_event = msg
                    samples = msg.get("samples", [])
                    if samples:
                        pred = studio_state.predict(samples)
                        print(f"🧠 [Waveform Stream] {msg.get('gesture', '')} | Peak: {msg.get('peak', 0.0):.1f} m/s² | PyTorch Prediction: {pred['pred_name']} ({pred['confidence']}%)")

            except json.JSONDecodeError:
                pass
            except Exception as e:
                print(f"[Warning] Error handling packet: {e}")

    except KeyboardInterrupt:
        print("\n👋 Server stopped by user.")
    finally:
        sock.close()

if __name__ == "__main__":
    port = 8765
    if len(sys.argv) > 1:
        try:
            port = int(sys.argv[1])
        except ValueError:
            pass
    run_udp_server(port)
