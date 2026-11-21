# -*- coding: utf-8 -*-
"""GUI приложения Suno Downloader (CustomTkinter).

Раскладка (сверху вниз):
  1. Шапка (заголовок + подзаголовок)
  2. Карточка: URL → Формат/Папка → Кнопка Загрузить
  3. Прогресс-бар + статус
  4. Карточка результата (появляется после загрузки)
  5. Журнал (свёрнут по умолчанию, кнопка +/− для раскрытия)
"""
import os
import queue
import re
import subprocess
import sys
import threading
from datetime import datetime

import customtkinter as ctk
from tkinter import filedialog, messagebox
from tkinter.scrolledtext import ScrolledText

from utils import default_downloads_folder, is_frozen
from downloader import SunoDownloader, SunoDownloadError, DownloadCancelled
import logger

# Палитра
BG = "#121216"
CARD = "#1C1C24"
ACCENT = "#6366F1"
ACCENT_HOVER = "#7C7FF5"
TEXT = "#FFFFFF"
TEXT_DIM = "#9CA3AF"
BORDER = "#2A2A35"
DANGER = "#EF4444"
OK = "#22C55E"
WARN = "#F59E0B"
LOG_BG = "#0D0D11"
LOG_FONT = ("Consolas", 10)


class App(ctk.CTk):
    def __init__(self):
        super().__init__()
        self.title("Suno MP3 Downloader")
        self.geometry("700x520")
        self.minsize(700, 480)
        self.maxsize(700, 720)
        self.configure(fg_color=BG)
        ctk.set_appearance_mode("dark")

        self._q = queue.Queue()
        self._busy = False
        self._dl = None
        self._log_visible = False  # По умолчанию журнал скрыт
        self._save_dir = default_downloads_folder()  # Полный путь папки сохранения

        self._build_ui()
        self.after(80, self._poll_queue)
        self._log("INFO", "Приложение запущено.")

    # ------------------------------------------------------------------ #
    def _log(self, level, msg):
        ts = datetime.now().strftime("%H:%M:%S")
        line = f"[{ts}] [{level:5s}] {msg}"
        try:
            self.log_text.insert("end", line + "\n")
            self.log_text.see("end")
        except Exception:
            pass
        lvl_map = {"INFO": "info", "DEBUG": "debug", "WARN": "warn",
                   "ERROR": "error", "OK": "ok"}
        fn = getattr(logger, lvl_map.get(level, "info"), logger.info)
        fn("GUI", msg)

    def _clear_log(self):
        self.log_text.delete("1.0", "end")

    def _open_log_file(self):
        """Открывает файл журнала в проводнике."""
        try:
            log_path = logger.get_log_path()
            if os.name == "nt":
                os.system(f'explorer /select,"{log_path}"')
            else:
                subprocess.Popen(["xdg-open", os.path.dirname(log_path)])
        except Exception as e:
            self._log("ERROR", f"Журнал: {e}")

    def _toggle_log(self):
        """Сворачивает/разворачивает блок журнала."""
        self._log_visible = not self._log_visible
        if self._log_visible:
            self.log_body.pack(fill="both", expand=True, padx=12, pady=(4, 10))
            self.btn_toggle.configure(text="−")
            # Увеличиваем окно, чтобы показать лог
            self.geometry("700x640")
        else:
            self.log_body.pack_forget()
            self.btn_toggle.configure(text="+")
            # Уменьшаем окно обратно
            self.geometry("700x520")

    # ------------------------------------------------------------------ #
    def _build_ui(self):
        pad = {"padx": 24}

        # ---------- 1. Шапка ---------- #
        header = ctk.CTkFrame(self, fg_color="transparent")
        header.pack(fill="x", **pad, pady=(14, 2))
        ctk.CTkLabel(header, text="Suno MP3 Downloader",
                     font=ctk.CTkFont(size=22, weight="bold"),
                     text_color=TEXT).pack(anchor="w")
        ctk.CTkLabel(
            header,
            text="Вставьте ссылку на публичную песню Suno\n"
                 "(https://suno.com/s/... или https://suno.com/song/...)",
            font=ctk.CTkFont(size=11), text_color=TEXT_DIM, anchor="w",
            justify="left").pack(anchor="w", pady=(2, 0))

        # ---------- 2. Рабочая карточка ---------- #
        card = ctk.CTkFrame(self, fg_color=CARD, corner_radius=14)
        card.pack(fill="x", **pad, pady=(6, 6))
        inner = ctk.CTkFrame(card, fg_color="transparent")
        inner.pack(fill="both", expand=True, padx=16, pady=12)

        # --- Поле ссылки --- #
        row_url = ctk.CTkFrame(inner, fg_color="transparent")
        row_url.pack(fill="x")
        self.entry = ctk.CTkEntry(
            row_url, placeholder_text="https://suno.com/s/...",
            height=40, font=ctk.CTkFont(size=13),
            border_color=BORDER, fg_color="#15151B")
        self.entry.pack(side="left", fill="x", expand=True)
        self.entry.bind("<Return>", lambda e: self._on_download())

        ctk.CTkButton(row_url, text="Вставить", width=68, height=40,
                      fg_color="transparent", hover_color="#26262F",
                      border_width=1, border_color=BORDER,
                      font=ctk.CTkFont(size=12),
                      command=self._on_paste).pack(side="left", padx=(8, 0))
        ctk.CTkButton(row_url, text="✕", width=36, height=40,
                      fg_color="transparent", hover_color="#26262F",
                      border_width=1, border_color=BORDER,
                      font=ctk.CTkFont(size=14),
                      command=self._on_clear).pack(side="left", padx=(8, 0))

        # --- Формат + Папка (одна строка) --- #
        row_fmt_dir = ctk.CTkFrame(inner, fg_color="transparent")
        row_fmt_dir.pack(fill="x", pady=(10, 0))

        fmt_box = ctk.CTkFrame(row_fmt_dir, fg_color="transparent")
        fmt_box.pack(side="left")
        ctk.CTkLabel(fmt_box, text="Формат:",
                     font=ctk.CTkFont(size=11), text_color=TEXT_DIM).pack(side="left")
        self.fmt_var = ctk.StringVar(value="MP3 (256 kbps)")
        for txt in ["MP3 (256 kbps)", "M4A (оригинал)"]:
            rb = ctk.CTkRadioButton(
                fmt_box, text=txt, variable=self.fmt_var, value=txt,
                font=ctk.CTkFont(size=11), fg_color=ACCENT,
                hover_color=ACCENT_HOVER)
            rb.pack(side="left", padx=(8, 0))

        dir_box = ctk.CTkFrame(row_fmt_dir, fg_color="transparent")
        dir_box.pack(side="right")
        self.dir_label = ctk.CTkLabel(
            dir_box, text=self._shorten_path(self._save_dir),
            font=ctk.CTkFont(size=11), text_color=TEXT_DIM, anchor="e",
            width=280)
        self.dir_label.pack(side="left")
        ctk.CTkButton(dir_box, text="Обзор...", width=64, height=28,
                      fg_color="transparent", hover_color="#26262F",
                      border_width=1, border_color=BORDER,
                      font=ctk.CTkFont(size=10),
                      command=self._on_browse).pack(side="left", padx=(8, 0))

        # --- Кнопка Загрузить --- #
        self.btn_dl = ctk.CTkButton(
            inner, text="⬇  Загрузить", height=48,
            font=ctk.CTkFont(size=16, weight="bold"),
            fg_color=ACCENT, hover_color=ACCENT_HOVER,
            command=self._on_download)
        self.btn_dl.pack(fill="x", pady=(14, 0))

        # ---------- 3. Статус + прогресс ---------- #
        status = ctk.CTkFrame(self, fg_color="transparent")
        status.pack(fill="x", **pad)
        self.progress = ctk.CTkProgressBar(status, height=6,
                                           progress_color=ACCENT)
        self.progress.set(0)
        self.progress.pack(fill="x")
        self.status_label = ctk.CTkLabel(
            status, text="Готов к работе.",
            font=ctk.CTkFont(size=11), text_color=TEXT_DIM, anchor="w")
        self.status_label.pack(anchor="w", pady=(4, 0))

        # ---------- 4. Карточка результата (скрыта до загрузки) ---------- #
        self.result_card = ctk.CTkFrame(self, fg_color="#15201a",
                                        corner_radius=12,
                                        border_width=1, border_color="#1f3a2c")
        # Не pack'им сразу — появится после загрузки

        # ---------- 5. Журнал (внизу, свёрнут по умолчанию) ---------- #
        self.log_frame = ctk.CTkFrame(self, fg_color=CARD, corner_radius=10)
        self.log_frame.pack(side="bottom", fill="x", **pad, pady=(8, 12))

        # Шапка журнала: заголовок + кнопки
        log_header = ctk.CTkFrame(self.log_frame, fg_color="transparent")
        log_header.pack(fill="x", padx=12, pady=(8, 0))

        # Левая часть: кнопка +/− и заголовок
        left_box = ctk.CTkFrame(log_header, fg_color="transparent")
        left_box.pack(side="left")

        self.btn_toggle = ctk.CTkButton(
            left_box, text="+", width=24, height=24,
            fg_color="transparent", hover_color="#26262F",
            border_width=1, border_color=BORDER,
            font=ctk.CTkFont(size=14, weight="bold"),
            command=self._toggle_log)
        self.btn_toggle.pack(side="left")

        ctk.CTkLabel(left_box, text="  Журнал",
                     font=ctk.CTkFont(size=11, weight="bold"),
                     text_color=TEXT_DIM).pack(side="left")

        # Правая часть: Открыть + Очистить
        ctk.CTkButton(log_header, text="Очистить", width=56, height=22,
                      fg_color="transparent", hover_color="#26262F",
                      border_width=1, border_color=BORDER,
                      font=ctk.CTkFont(size=9),
                      command=self._clear_log).pack(side="right")
        ctk.CTkButton(log_header, text="📄 Открыть", width=64, height=22,
                      fg_color="transparent", hover_color="#26262F",
                      border_width=1, border_color=BORDER,
                      font=ctk.CTkFont(size=9),
                      command=self._open_log_file).pack(side="right", padx=(0, 6))

        # Тело журнала (скрываем по умолчанию)
        self.log_body = ctk.CTkFrame(self.log_frame, fg_color="transparent")
        self.log_text = ScrolledText(
            self.log_body, height=4, font=LOG_FONT,
            bg=LOG_BG, fg=TEXT_DIM,
            insertbackground=TEXT,
            relief="flat", wrap="word", state="normal")
        self.log_text.pack(fill="both", expand=True)
        # log_body НЕ pack'им — по умолчанию скрыт

    # ------------------------------------------------------------------ #
    def _on_paste(self):
        try:
            txt = self.clipboard_get().strip()
        except Exception:
            return
        if txt:
            self.entry.delete(0, "end")
            self.entry.insert(0, txt)

    def _on_clear(self):
        self.entry.delete(0, "end")

    def _on_browse(self):
        d = filedialog.askdirectory(initialdir=self.dir_label.cget("text"))
        if d:
            self._save_dir = d
            self.dir_label.configure(text=self._shorten_path(d))

    @staticmethod
    def _shorten_path(p, max_len=40):
        """Укорачивает длинный путь с эллипсисом."""
        if len(p) <= max_len:
            return p
        return "..." + p[-(max_len - 3):]

    def _set_busy(self, busy):
        self._busy = busy
        if busy:
            self.btn_dl.configure(state="normal", text="⏹  Остановить")
            self.entry.configure(state="disabled")
        else:
            self.btn_dl.configure(state="normal", text="⬇  Загрузить")
            self.entry.configure(state="normal")

    # ------------------------------------------------------------------ #
    def _on_download(self):
        if self._busy:
            if self._dl is not None:
                self._dl.cancel()
                self.status_label.configure(text="Останавливаю...", text_color=WARN)
                self._log("WARN", "Загрузка остановлена пользователем.")
            return
        url = self.entry.get().strip()
        if not url:
            messagebox.showwarning("Нет ссылки",
                                   "Вставьте ссылку на трек Suno.")
            return
        # Валидация формата URL до запуска сети
        if not re.match(r'^https?://suno\.com/(s|song)/[\w-]+', url):
            messagebox.showwarning(
                "Неверная ссылка",
                "Введите ссылку вида:\n"
                "https://suno.com/s/...\n"
                "или\n"
                "https://suno.com/song/<uuid>")
            return
        folder = self._save_dir or default_downloads_folder()

        fmt_val = self.fmt_var.get()
        fmt = "mp3" if "MP3" in fmt_val else "m4a"

        self.result_card.pack_forget()
        self.progress.set(0)
        self._set_busy(True)
        self.status_label.configure(text="Начинаю...", text_color=TEXT_DIM)
        self._log("INFO", f"Загрузка: {url[:80]} → {fmt.upper()}")

        def _worker():
            dl = SunoDownloader(
                output_dir=folder,
                log_callback=lambda lvl, msg: self._q.put(("log", (lvl, msg))),
                status_callback=lambda s: self._q.put(("status", s)),
                progress_callback=lambda cur, tot: self._q.put(("progress", cur)),
            )
            self._dl = dl
            try:
                result = dl.process(url, fmt=fmt)
                self._q.put(("done", {"path": result.path, "title": result.title,
                                      "folder": folder, "size_kb": result.size_kb}))
            except DownloadCancelled:
                self._q.put(("cancelled", None))
            except SunoDownloadError as e:
                self._q.put(("error", str(e)))
            except Exception as e:
                self._q.put(("error", f"{e.__class__.__name__}: {str(e)[:200]}"))
            finally:
                self._dl = None

        threading.Thread(target=_worker, daemon=True).start()

    # ------------------------------------------------------------------ #
    def _poll_queue(self):
        try:
            while True:
                kind, payload = self._q.get_nowait()
                if kind == "status":
                    self.status_label.configure(text=payload, text_color=TEXT_DIM)
                elif kind == "progress":
                    if payload is not None and payload > 0:
                        self.progress.set(payload / 100)
                elif kind == "log":
                    lvl, msg = payload
                    self._log(lvl, msg)
                elif kind == "done":
                    self.progress.set(1.0)
                    self.status_label.configure(text="Готово!", text_color=OK)
                    self._log("OK", f"Сохранено: {payload['path']}")
                    self._show_result(payload)
                    self._set_busy(False)
                elif kind == "cancelled":
                    self.status_label.configure(text="Остановлено.", text_color=WARN)
                    self._log("WARN", "Загрузка остановлена.")
                    self._set_busy(False)
                elif kind == "error":
                    self.status_label.configure(text=payload[:80], text_color=DANGER)
                    self._log("ERROR", payload)
                    self._set_busy(False)
                    messagebox.showerror("Ошибка", payload)
        except queue.Empty:
            pass
        self.after(80, self._poll_queue)

    def _show_result(self, res):
        for w in self.result_card.winfo_children():
            w.destroy()

        body = ctk.CTkFrame(self.result_card, fg_color="transparent")
        body.pack(fill="x", padx=14, pady=10)

        top = ctk.CTkFrame(body, fg_color="transparent")
        top.pack(fill="x")
        ctk.CTkLabel(top, text="✓  Файл сохранён",
                     font=ctk.CTkFont(size=13, weight="bold"),
                     text_color=OK, anchor="w").pack(side="left")
        ctk.CTkLabel(top, text=f"{res.get('size_kb', 0)} КБ",
                     font=ctk.CTkFont(size=11), text_color=TEXT_DIM,
                     anchor="e").pack(side="right")

        ctk.CTkLabel(body, text=res.get("title", os.path.basename(res["path"])),
                     font=ctk.CTkFont(size=12), text_color=TEXT,
                     anchor="w").pack(anchor="w", pady=(4, 0))

        bottom = ctk.CTkFrame(body, fg_color="transparent")
        bottom.pack(fill="x", pady=(8, 0))
        ctk.CTkLabel(bottom, text=res["path"],
                     font=ctk.CTkFont(size=10), text_color=TEXT_DIM,
                     anchor="w").pack(side="left", fill="x", expand=True)
        ctk.CTkButton(bottom, text="📂 Открыть папку",
                      fg_color=ACCENT, hover_color=ACCENT_HOVER,
                      font=ctk.CTkFont(size=11, weight="bold"),
                      width=130, height=32,
                      command=lambda: self._open_folder(res["folder"]))\
            .pack(side="right", padx=(10, 0))

        # Карточка между прогресс-баром и журналом
        self.result_card.pack(fill="x", padx=24, pady=(8, 0), before=self.log_frame)

    def _open_folder(self, folder):
        if not folder or not os.path.isdir(folder):
            return
        try:
            if os.name == "nt":
                os.startfile(folder)
            else:
                subprocess.Popen(["xdg-open", folder])
        except Exception as e:
            self._log("ERROR", f"Папка: {e}")
