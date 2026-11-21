# -*- coding: utf-8 -*-
"""Модуль логирования Suno Downloader v3.1.

Пишет в файл suno_downloader.log.
Путь: %LOCALAPPDATA%\\SunoDownloader\\suno_downloader.log
(гарантированно доступен для записи, не зависит от расположения .exe)

Формат: [YYYY-MM-DD HH:MM:SS.mmm] [LEVEL] [MODULE] message
Ротация: при размере > 2 МБ → suno_downloader.log.old
"""
import os
import sys
import threading
from datetime import datetime

_LOG_FILE = None
_LOCK = threading.Lock()
_MAX_SIZE = 2 * 1024 * 1024  # 2 MB


def _log_dir():
    """Каталог для логов: %LOCALAPPDATA%\\SunoDownloader."""
    local_appdata = os.environ.get("LOCALAPPDATA", "")
    if local_appdata:
        d = os.path.join(local_appdata, "SunoDownloader")
    else:
        # Fallback: домашняя директория
        d = os.path.expanduser("~")
    os.makedirs(d, exist_ok=True)
    return d


def _log_path():
    global _LOG_FILE
    if _LOG_FILE is not None:
        return _LOG_FILE
    _LOG_FILE = os.path.join(_log_dir(), "suno_downloader.log")
    return _LOG_FILE


def _rotate():
    """Ротирует журнал если превышен лимит."""
    path = _log_path()
    try:
        if os.path.isfile(path) and os.path.getsize(path) > _MAX_SIZE:
            old = path + ".old"
            if os.path.exists(old):
                os.remove(old)
            os.rename(path, old)
    except Exception:
        pass


def log(level: str, module: str, msg: str):
    """Записывает строку в файл журнала. Блокирующая, потокобезопасная."""
    ts = datetime.now().strftime("%Y-%m-%d %H:%M:%S.%f")[:-3]
    line = f"[{ts}] [{level:5s}] [{module}] {msg}"
    with _LOCK:
        try:
            _rotate()
            with open(_log_path(), "a", encoding="utf-8") as f:
                f.write(line + "\n")
        except Exception:
            pass
    # Вывод в stdout для отладки при запуске из исходников
    if not getattr(sys, "frozen", False):
        print(line, flush=True)


def debug(module: str, msg: str):
    log("DEBUG", module, msg)


def info(module: str, msg: str):
    log("INFO", module, msg)


def warn(module: str, msg: str):
    log("WARN", module, msg)


def error(module: str, msg: str):
    log("ERROR", module, msg)


def ok(module: str, msg: str):
    log("OK", module, msg)


def exception(module: str, exc: Exception):
    """Логирует исключение с traceback."""
    import traceback
    tb = traceback.format_exception(type(exc), exc, exc.__traceback__)
    error(module, f"{exc.__class__.__name__}: {str(exc)[:300]}")
    for line in tb:
        if line.strip():
            debug(module, "TB| " + line.rstrip())


def session_start(url: str, fmt: str, output_dir: str):
    """Лог начала сессии скачивания."""
    info("SESSION", "=" * 60)
    info("SESSION", f"URL: {url}")
    info("SESSION", f"Format: {fmt.upper()}")
    info("SESSION", f"Output: {output_dir}")
    info("SESSION", f"Python: {sys.version.split()[0]}, frozen={getattr(sys, 'frozen', False)}")
    info("SESSION", f"Platform: {sys.platform}")
    info("SESSION", "=" * 60)


def session_end(elapsed: float, success: bool, result_path: str = ""):
    """Лог завершения сессии."""
    status = "SUCCESS" if success else "FAILED"
    info("SESSION", f"{'=' * 60}")
    info("SESSION", f"Result: {status} ({elapsed:.1f}s)")
    if result_path:
        info("SESSION", f"File: {result_path}")
    info("SESSION", f"{'=' * 60}")


def get_log_path() -> str:
    """Возвращает путь к файлу журнала (для GUI)."""
    return _log_path()
