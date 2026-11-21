"""Playback window for a user-selected intro video."""

import os
import subprocess
import tempfile
import time
from pathlib import Path
import winsound

import customtkinter as ctk
import tkinter as tk
from PIL import Image, ImageTk


class DeskTwinIntroOverlay(ctk.CTkFrame):
    def __init__(self, parent, on_finish, video_path=None):
        super().__init__(parent, fg_color="#071426", corner_radius=0)
        self._on_finish = on_finish
        self._after_id = None
        self._closed = False
        if not video_path:
            raise ValueError("لا يوجد فيديو مقدمة اختاره المستخدم")
        asset = Path(video_path)
        if asset.suffix.lower() not in {".mp4", ".mov", ".mkv", ".avi", ".webm", ".m4v"}:
            raise ValueError("صيغة فيديو المقدمة غير مدعومة")
        import imageio_ffmpeg
        self._ffmpeg_exe = imageio_ffmpeg.get_ffmpeg_exe()
        self._frame_iterator = imageio_ffmpeg.read_frames(str(asset), pix_fmt="rgb24")
        video_info = next(self._frame_iterator)
        self._frame_width, self._frame_height = video_info["size"]
        self._video_fps = min(60.0, max(1.0, float(video_info.get("fps", 30))))
        self._frame_index = 0
        self._started_at = None
        self._audio_path = self._extract_audio_track(asset)
        self._tk_frame = None

        self.canvas = tk.Canvas(self, bg="#071426", highlightthickness=0, bd=0)
        self.canvas.pack(fill="both", expand=True)
        self.skip = ctk.CTkButton(
            self, text="Skip", width=76, height=30, corner_radius=12,
            fg_color="#19324E", hover_color="#244563", text_color="#EAFBFF",
            command=self.finish,
        )
        self.skip.place(relx=0.985, rely=0.025, anchor="ne")
        self.lift()
        self._after_id = self.after(0, self._tick)

    def _extract_audio_track(self, video_path):
        descriptor, wav_path = tempfile.mkstemp(prefix="desktwin-intro-", suffix=".wav")
        os.close(descriptor)
        command = [
            self._ffmpeg_exe, "-y", "-hide_banner", "-loglevel", "error",
            "-i", str(video_path), "-map", "0:a:0", "-vn",
            "-acodec", "pcm_s16le", "-ar", "44100", "-ac", "2", wav_path,
        ]
        try:
            subprocess.run(
                command, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                timeout=120, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            )
            return wav_path if os.path.getsize(wav_path) > 44 else self._remove_temp_audio(wav_path)
        except (OSError, subprocess.SubprocessError):
            return self._remove_temp_audio(wav_path)

    @staticmethod
    def _remove_temp_audio(path):
        try:
            os.remove(path)
        except OSError:
            pass
        return None

    def finish(self):
        if self._closed:
            return
        self._closed = True
        if self._after_id is not None:
            try:
                self.after_cancel(self._after_id)
            except tk.TclError:
                pass
            self._after_id = None
        try:
            winsound.PlaySound(None, 0)
        except RuntimeError:
            pass
        if self._audio_path:
            self._remove_temp_audio(self._audio_path)
            self._audio_path = None
        self._frame_iterator.close()
        self._on_finish()

    def _tick(self):
        if self._closed or not self.winfo_exists():
            return
        if self._started_at is None:
            self._started_at = time.monotonic()
            if self._audio_path:
                try:
                    winsound.PlaySound(
                        self._audio_path, winsound.SND_FILENAME | winsound.SND_ASYNC
                    )
                except RuntimeError:
                    pass

        target_frame = int((time.monotonic() - self._started_at) * self._video_fps)
        try:
            while self._frame_index < target_frame:
                next(self._frame_iterator)
                self._frame_index += 1
            raw_frame = next(self._frame_iterator)
            self._frame_index += 1
            frame = Image.frombytes("RGB", (self._frame_width, self._frame_height), raw_frame)
        except (StopIteration, OSError, RuntimeError, ValueError):
            self.finish()
            return

        width, height = max(1, self.canvas.winfo_width()), max(1, self.canvas.winfo_height())
        fps = self._video_fps
        self.canvas.configure(bg="#071426")
        scale = min(width / frame.width, height / frame.height)
        size = (max(1, round(frame.width * scale)), max(1, round(frame.height * scale)))
        frame = frame.resize(size, Image.Resampling.BILINEAR)
        self._tk_frame = ImageTk.PhotoImage(frame)
        self.canvas.delete("all")
        self.canvas.create_image(width // 2, height // 2, image=self._tk_frame)
        self.lift()

        next_frame_time = self._started_at + self._frame_index / fps
        delay_ms = max(1, round((next_frame_time - time.monotonic()) * 1000))
        self._after_id = self.after(delay_ms, self._tick)
