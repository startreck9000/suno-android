"""Утилиты: очистка имён файлов для Windows, уникализация имени, поиск FFmpeg."""
import os
import re
import sys


# Символы, запрещённые в именах файлов Windows
_FORBIDDEN = re.compile(r'[\\/:*?"<>|]')


def sanitize_filename(name: str, max_len: int = 150) -> str:
    """Очищает строку от символов, запрещённых в именах файлов Windows.

    Убирает \\ / : * ? " < > |, схлопывает лишние пробелы/точки,
    ограничивает длину. Если после очистки имя пустое — возвращает 'track'.
    """
    if not name:
        return "track"
    name = _FORBIDDEN.sub("", name)
    # заменяем переносы строк и табы пробелом
    name = re.sub(r"\s+", " ", name)
    # убираем ведущие/хвостовые точки и пробелы (проблема Windows)
    name = name.strip(" .")
    name = name[:max_len].strip(" .")
    return name or "track"


def unique_path(folder: str, filename: str) -> str:
    """Возвращает путь к файлу в папке, не перезаписывая существующий.

    При конфликте добавляет индекс (1), (2), ... перед расширением.
    """
    base, ext = os.path.splitext(filename)
    candidate = os.path.join(folder, filename)
    counter = 1
    while os.path.exists(candidate):
        candidate = os.path.join(folder, f"{base} ({counter}){ext}")
        counter += 1
    return candidate


def default_downloads_folder() -> str:
    """Системная папка «Загрузки» (Downloads).

    Проверяет локализованные имена (Downloads / Загрузки) и Known Folder
    из реестра Windows. Фолбэк — домашняя директория.
    """
    home = os.path.expanduser("~")
    # 1) Стандартные имена папки (EN / RU)
    for name in ("Downloads", "Загрузки"):
        p = os.path.join(home, name)
        if os.path.isdir(p):
            return p
    # 2) Known Folder Downloads (GUID) из реестра Windows
    if os.name == "nt":
        try:
            import winreg
            key = winreg.OpenKey(
                winreg.HKEY_CURRENT_USER,
                r"Software\Microsoft\Windows\CurrentVersion\Explorer\User Shell Folders")
            val, _ = winreg.QueryValueEx(
                key, "{374DE290-123F-4565-9164-39C4925E467B}")
            winreg.CloseKey(key)
            val = os.path.expandvars(val)
            if os.path.isdir(val):
                return val
        except Exception:
            pass
    # 3) Фолбэк: домашняя директория
    return home


def is_frozen() -> bool:
    """True, если код запущен из собранного PyInstaller-бинарника."""
    return getattr(sys, "frozen", False)


def resource_dir() -> str:
    """Каталог, куда PyInstaller распаковывает --add-binary ресурсы при запуске."""
    if is_frozen():
        # sys._MEIPASS — временный каталог распаковки one-file сборки
        return getattr(sys, "_MEIPASS", os.path.dirname(sys.executable))
    return os.path.dirname(os.path.abspath(__file__))


def find_ffmpeg() -> str:
    """Ищет ffmpeg.exe: сначала рядом с бинарником/ресурсами, потом в PATH.

    Возвращает абсолютный путь к исполняемому файлу или пустую строку,
    если ничего не найдено.
    """
    exe_name = "ffmpeg.exe" if os.name == "nt" else "ffmpeg"

    candidates = []
    # 1) Ресурсы PyInstaller (вшитый ffmpeg.exe)
    try:
        candidates.append(os.path.join(resource_dir(), exe_name))
    except Exception:
        pass
    # 2) Рядом с текущим скриптом/бинарником
    try:
        if is_frozen():
            candidates.append(os.path.join(os.path.dirname(sys.executable), exe_name))
        else:
            candidates.append(os.path.join(os.path.dirname(os.path.abspath(__file__)), exe_name))
    except Exception:
        pass
    # 3) Системный PATH
    for c in candidates:
        if os.path.isfile(c) and os.access(c, os.X_OK):
            return c

    # 4) shutil.which по PATH
    import shutil
    found = shutil.which(exe_name)
    if found:
        return found
    return ""
