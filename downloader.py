# -*- coding: utf-8 -*-
"""Suno Downloader — бэкенд v4.0.

Новый алгоритм (без Chrome/CDP):
  1. Resolve: /s/<code> → /song/<uuid> (GET, редирект)
  2. Title: studio-api-prod.suno.com/api/oembed
  3. Rights: POST studio-api-prod.suno.com/api/mango/rights
  4. Download: CloudFront encrypted stream (.m4a)
  5. Decrypt: AES-GCM unwrap key/iv + AES-CTR audio
  6. Convert: FFmpeg → MP3 256 kbps (или M4A/MP4 как есть)
  7. Metadata: ID3 tags + cover art via FFmpeg
"""
import base64
import hashlib
import json
import os
import re
import shutil
import subprocess
import tempfile
import time
import urllib.parse
from collections import Counter

import requests

import logger
from utils import sanitize_filename, unique_path, find_ffmpeg

# ─── Constants ────────────────────────────────────────────────────────────────
USER_AGENT = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
              "AppleWebKit/537.36 (KHTML, like Gecko) "
              "Chrome/126.0.0.0 Safari/537.36")
API_BASE = "https://studio-api-prod.suno.com"
RIGHTS_URL = f"{API_BASE}/api/mango/rights"
OEMBED_URL = f"{API_BASE}/api/oembed"
CDN_TEMPLATE = "https://d2lwuy8qc234o3.cloudfront.net/1/clip/{clip}.m4a"
CDN_FALLBACK = "https://d2lwuy8qc234o3.cloudfront.net/1/clip/{clip}.webm"
COVER_URLS = [
    "https://cdn2.suno.ai/image_large_{clip}.jpeg",
    "https://cdn.suno.ai/image_large_{clip}.jpeg",
    "https://cdn2.suno.ai/image_{clip}.jpeg",
]
TIMEOUT = 30
SONG_RE = re.compile(
    r"/song/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})",
    re.I,
)
UUID_RE = re.compile(
    r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", re.I
)


class SunoDownloadError(Exception):
    """Ошибка скачивания с человекочитаемым сообщением."""
    pass


class DownloadCancelled(Exception):
    """Загрузка остановлена пользователем (не ошибка)."""
    pass


# ─── HTTP helpers ─────────────────────────────────────────────────────────────

def _headers():
    return {
        "User-Agent": USER_AGENT,
        "Referer": "https://suno.com/",
        "Origin": "https://suno.com",
    }


def http_get(url: str, timeout: int = TIMEOUT) -> bytes:
    resp = requests.get(url, headers=_headers(), timeout=timeout)
    resp.raise_for_status()
    return resp.content


def http_post_json(url: str, payload: dict, timeout: int = 20) -> dict:
    resp = requests.post(
        url, json=payload,
        headers={**_headers(), "Content-Type": "application/json"},
        timeout=timeout,
    )
    resp.raise_for_status()
    return resp.json()


# ─── Step 1: Resolve link to clip UUID ────────────────────────────────────────

def resolve_clip_id(link: str) -> str:
    """Прямая ссылка /song/<uuid> или короткая /s/<code>."""
    m = SONG_RE.search(link)
    if m:
        return m.group(1).lower()

    # Короткая ссылка: GET и ищем UUID в HTML/редиректе
    logger.info("RESOLVE", f"Short link detected: {link}")
    try:
        resp = requests.get(link, headers=_headers(), timeout=TIMEOUT, allow_redirects=True)
        final_url = resp.url
        logger.debug("RESOLVE", f"GET final URL: {final_url}")

        # UUID в финальном URL
        m = SONG_RE.search(final_url)
        if m:
            return m.group(1).lower()

        # UUID в HTML
        html = resp.text
        m = SONG_RE.search(html)
        if m:
            return m.group(1).lower()

        # Любой UUID в странице (наиболее частый)
        all_uuids = [u.lower() for u in UUID_RE.findall(html)]
        if all_uuids:
            most_common = Counter(all_uuids).most_common(1)[0][0]
            logger.info("RESOLVE", f"Resolved via HTML: {most_common}")
            return most_common

    except requests.RequestException as e:
        logger.error("RESOLVE", f"GET failed: {e}")

    raise SunoDownloadError(
        "Не удалось определить ID трека по ссылке.\n\n"
        "Проверьте, что ссылка ведёт на публичный трек Suno:\n"
        "• https://suno.com/song/<uuid>\n"
        "• https://suno.com/s/<код>"
    )


# ─── Step 2: Get title via oembed ─────────────────────────────────────────────

def get_title(clip: str) -> str:
    url = f"{OEMBED_URL}?url=" + urllib.parse.quote(f"https://suno.com/song/{clip}", safe="")
    try:
        data = json.loads(http_get(url).decode())
        title = data.get("title", "")
        if title:
            logger.info("META", f"Title: {title}")
            return title
    except Exception as e:
        logger.warn("META", f"oembed failed: {e}")
    return clip[:8]


# ─── Step 3: Request playback rights ──────────────────────────────────────────

def get_rights(clip: str) -> dict:
    logger.info("RIGHTS", f"Requesting playback rights for {clip}...")
    payload = {"content_params": {"content_id": clip, "content_type": "clip"}}
    rights = http_post_json(RIGHTS_URL, payload)
    if not all(k in rights for k in ("key", "iv", "glt")):
        raise SunoDownloadError(
            f"Непредвиденный ответ от сервера прав:\n{json.dumps(rights)[:200]}"
        )
    logger.info("RIGHTS", "Rights obtained OK")
    return rights


# ─── Step 4: Download encrypted stream ────────────────────────────────────────

def download_encrypted(clip: str) -> tuple[bytes, str]:
    """Возвращает (encrypted_bytes, extension)."""
    for template, ext in ((CDN_TEMPLATE, ".m4a"), (CDN_FALLBACK, ".webm")):
        url = template.format(clip=clip)
        try:
            logger.info("DOWNLOAD", f"Fetching: {url}")
            data = http_get(url, timeout=60)
            logger.info("DOWNLOAD", f"Downloaded {len(data) // 1024} KB ({ext})")
            return data, ext
        except requests.RequestException as e:
            logger.warn("DOWNLOAD", f"{ext} failed: {e}, trying next...")
    raise SunoDownloadError(
        "Не удалось скачать аудиопоток.\n\n"
        "Причины:\n"
        "• Трек приватный или удалён\n"
        "• CDN временно недоступен\n\n"
        "Попробуйте другой публичный трек."
    )


# ─── Step 5: Decrypt ──────────────────────────────────────────────────────────

def decrypt_clip(clip: str, enc: bytes, rights: dict) -> bytes:
    from Crypto.Cipher import AES

    user_key = hashlib.sha256(rights["glt"].encode()).digest()

    def unwrap(pkg_b64: str) -> bytes:
        """AES-GCM decrypt: raw = nonce(12) + ciphertext + tag(16)."""
        raw = base64.b64decode(pkg_b64)
        nonce = raw[:12]
        ct_with_tag = raw[12:]
        tag = ct_with_tag[-16:]
        ct = ct_with_tag[:-16]
        cipher = AES.new(user_key, AES.MODE_GCM, nonce=nonce)
        cipher.update(clip.encode())  # AAD (до decrypt!)
        return cipher.decrypt_and_verify(ct, tag)

    content_key = unwrap(rights["key"])
    content_iv = unwrap(rights["iv"])

    # AES-CTR decrypt — ручная реализация через ECB (надёжно на любой версии PyCryptodome)
    ecb = AES.new(content_key, AES.MODE_ECB)
    plain = bytearray()
    counter = content_iv  # 16-байтовый начальный счётчик

    for i in range(0, len(enc), 16):
        keystream = ecb.encrypt(counter)
        chunk = enc[i:i+16]
        if len(chunk) == 16:
            # Полный блок: быстрый XOR через целые числа
            ks_int = int.from_bytes(keystream, 'big')
            ct_int = int.from_bytes(chunk, 'big')
            plain.extend((ks_int ^ ct_int).to_bytes(16, 'big'))
        else:
            # Последний (короткий) блок: побайтовый XOR
            plain.extend(a ^ b for a, b in zip(chunk, keystream))
        # Инкремент счётчика (big-endian)
        n = int.from_bytes(counter, 'big') + 1
        counter = n.to_bytes(16, 'big')

    plain = bytes(plain)

    # Проверка: M4A начинается с 'ftyp', WebM с 0x1A45DFA3
    if b"ftyp" not in plain[:50] and b"\x1a\x45\xdf\xa3" not in plain[:10]:
        raise SunoDownloadError(
            "Ошибка расшифровки аудио.\n\n"
            "Ключи доступа быстро устаревают. Попробуйте ещё раз."
        )
    logger.info("DECRYPT", f"Decrypted OK: {len(plain) // 1024} KB")
    return plain


# ─── Step 6: Cover art ────────────────────────────────────────────────────────

def download_cover(clip: str) -> bytes | None:
    for url_tpl in COVER_URLS:
        url = url_tpl.format(clip=clip)
        try:
            data = http_get(url, timeout=15)
            if len(data) > 1000:  # реальная картинка, не заглушка
                logger.info("COVER", f"Cover downloaded: {len(data) // 1024} KB")
                return data
        except Exception:
            continue
    logger.warn("COVER", "No cover found")
    return None


# ─── Step 7: FFmpeg conversion ────────────────────────────────────────────────

def convert_to_mp3(input_path: str, output_path: str,
                   title: str, artist: str,
                   cover_path: str | None) -> None:
    ffmpeg = find_ffmpeg()
    if not ffmpeg:
        raise SunoDownloadError(
            "FFmpeg не найден!\n\n"
            "Положите ffmpeg.exe рядом с программой "
            "(или пересоберите через build.bat)."
        )

    cmd = [ffmpeg, "-y", "-v", "error"]
    cmd += ["-i", input_path]

    # Обложка как второй вход
    if cover_path and os.path.isfile(cover_path):
        cmd += ["-i", cover_path, "-map", "0:a", "-map", "1:v"]

    cmd += [
        "-codec:a", "libmp3lame",
        "-b:a", "256k",
        "-metadata", f"title={title}",
        "-metadata", f"artist={artist or 'Suno'}",
    ]
    if cover_path and os.path.isfile(cover_path):
        cmd += ["-id3v2_version", "3", "-metadata:s:v", "title=Album cover",
                "-disposition:v", "attached_pic"]

    cmd.append(output_path)

    logger.info("FFMPEG", f"Command: {' '.join(cmd[:6])}...")
    t0 = time.time()
    result = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    elapsed = time.time() - t0

    if result.returncode != 0:
        stderr = result.stderr.decode(errors="ignore")[-500:]
        logger.error("FFMPEG", f"Failed (rc={result.returncode}): {stderr}")
        raise SunoDownloadError(f"Ошибка конвертации: {stderr[:200]}")

    size_mb = os.path.getsize(output_path) / (1024 * 1024)
    logger.ok("FFMPEG", f"Success: {output_path} ({size_mb:.1f} MB) in {elapsed:.1f}s")


# ─── Main entry point ─────────────────────────────────────────────────────────

class DownloadResult:
    def __init__(self, path, title, size_kb):
        self.path = path
        self.title = title
        self.size_kb = size_kb


class SunoDownloader:
    """Обёртка для GUI: совместимый интерфейс со старой версией."""

    def __init__(self, output_dir, log_callback=None,
                 status_callback=None, progress_callback=None):
        self.output_dir = output_dir
        self._log_cb = log_callback
        self._status_cb = status_callback
        self._progress_cb = progress_callback
        import threading
        self._cancel_event = threading.Event()

    def cancel(self):
        """Запрашивает остановку текущей загрузки."""
        self._cancel_event.set()

    def process(self, url, fmt="mp3") -> DownloadResult:
        def _progress(pct):
            if self._progress_cb:
                try:
                    self._progress_cb(pct, 100)
                except TypeError:
                    self._progress_cb(pct)

        result_path = download(
            url, fmt, self.output_dir,
            progress_cb=_progress,
            status_cb=self._status_cb,
            cancel_check=self._cancel_event.is_set,
        )
        size_kb = os.path.getsize(result_path) // 1024
        # Название из пути (без расширения)
        base = os.path.splitext(os.path.basename(result_path))[0]
        return DownloadResult(result_path, base, size_kb)


def download(url: str, fmt: str, output_dir: str,
             progress_cb=None, status_cb=None, cancel_check=None) -> str:
    """
    Главный вызов. Возвращает путь к итоговому файлу.

    Args:
        url: ссылка на трек Suno
        fmt: "mp3" | "m4a" | "mp4"
        output_dir: папка сохранения
        progress_cb: callable(percent: int)
        status_cb: callable(text: str)
    """
    t_start = time.time()
    logger.session_start(url, fmt, output_dir)

    def _status(msg):
        if status_cb:
            status_cb(msg)
        logger.info("GUI", msg)

    def _progress(pct):
        if progress_cb:
            progress_cb(pct)

    tmp_dir = tempfile.mkdtemp(prefix="sunodl_")

    def _check_cancel():
        if cancel_check and cancel_check():
            raise DownloadCancelled("Загрузка остановлена пользователем.")

    try:
        # 1. Resolve
        _status("Определение ID трека...")
        _progress(5)
        clip = resolve_clip_id(url)
        logger.info("DOWNLOAD", f"Song ID: {clip}")
        _status(f"ID: {clip[:8]}...")
        _progress(10)
        _check_cancel()

        # 2. Title
        _status("Получение названия...")
        title = get_title(clip)
        _progress(15)
        _check_cancel()

        # 3. Rights
        _status("Запрос доступа к аудио...")
        _progress(20)
        rights = get_rights(clip)
        _check_cancel()

        # 4. Download encrypted
        _status("Скачивание аудиопотока...")
        _progress(30)
        enc_data, enc_ext = download_encrypted(clip)
        _progress(50)
        _check_cancel()

        # 5. Decrypt
        _status("Расшифровка аудио...")
        _progress(60)
        plain = decrypt_clip(clip, enc_data, rights)

        # Сохраняем расшифрованный файл
        src_path = os.path.join(tmp_dir, f"audio{enc_ext}")
        with open(src_path, "wb") as f:
            f.write(plain)
        _progress(70)
        _check_cancel()

        # 6. Determine output format
        if fmt == "mp3":
            _status("Конвертация в MP3...")
            _progress(75)
            base_name = sanitize_filename(title)
            final_path = unique_path(output_dir, f"{base_name}.mp3")

            # Обложка
            cover_path = None
            cover_data = download_cover(clip)
            if cover_data:
                cover_path = os.path.join(tmp_dir, "cover.jpg")
                with open(cover_path, "wb") as f:
                    f.write(cover_data)
            _check_cancel()

            # Конвертация + метаданные
            convert_to_mp3(src_path, final_path, title, "Suno", cover_path)
            _progress(95)

        elif fmt == "m4a":
            _status("Сохранение M4A...")
            _progress(80)
            base_name = sanitize_filename(title)
            final_path = unique_path(output_dir, f"{base_name}.m4a")
            shutil.copy2(src_path, final_path)
            _progress(90)

        else:  # mp4 — пока нет видео, сохраняем м4а как есть
            _status("Сохранение аудио...")
            _progress(80)
            base_name = sanitize_filename(title)
            final_path = unique_path(output_dir, f"{base_name}{enc_ext}")
            shutil.copy2(src_path, final_path)
            _progress(90)

        _progress(100)
        _status("Готово!")

        elapsed = time.time() - t_start
        logger.session_end(elapsed, True, final_path)
        return final_path

    except DownloadCancelled as e:
        elapsed = time.time() - t_start
        logger.warn("SESSION", f"Cancelled by user ({elapsed:.1f}s)")
        logger.session_end(elapsed, False)
        raise
    except SunoDownloadError:
        elapsed = time.time() - t_start
        logger.session_end(elapsed, False)
        raise
    except requests.ConnectionError:
        elapsed = time.time() - t_start
        logger.session_end(elapsed, False)
        raise SunoDownloadError(
            "Нет соединения с интернетом.\n\n"
            "Проверьте подключение к сети."
        )
    except requests.Timeout:
        elapsed = time.time() - t_start
        logger.session_end(elapsed, False)
        raise SunoDownloadError(
            "Таймаут соединения.\n\n"
            "Сервер Suno не отвечает. Попробуйте позже."
        )
    except Exception as e:
        elapsed = time.time() - t_start
        logger.exception("FATAL", e)
        logger.session_end(elapsed, False)
        raise SunoDownloadError(f"Неожиданная ошибка: {e}")
    finally:
        # Cleanup
        try:
            shutil.rmtree(tmp_dir, ignore_errors=True)
        except Exception:
            pass
        logger.debug("CLEANUP", f"Temp dir removed: {tmp_dir}")
