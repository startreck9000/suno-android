"""Suno MP3 Downloader — точка входа.

Платформа: Windows 10/11 x64 (только 64-битная система).

Запуск из исходников:      python main.py
Запуск собранного .exe:    SunoDownloader.exe  (без консоли, --windowed)
"""
import platform
import struct
import sys


def _check_x64():
    """Блокирует запуск на 32-битном Python / 32-битной системе."""
    # struct.calcsize("P") == 8 только для 64-битного процесса
    if struct.calcsize("P") != 8:
        try:
            import tkinter
            root = tkinter.Tk()
            root.withdraw()
            from tkinter import messagebox
            messagebox.showerror(
                "Неподдерживаемая система",
                "Suno MP3 Downloader работает только на Windows 10/11 x64.\n\n"
                f"Обнаружен {platform.machine()} процесс (32-bit).\n"
                "Установите 64-битный Python: https://www.python.org/downloads/"
            )
            root.destroy()
        except Exception:
            print(f"ERROR: 32-bit runtime detected ({platform.machine()}). "
                  f"Requires 64-bit Windows 10/11 + 64-bit Python.")
        sys.exit(1)


def main():
    _check_x64()

    if getattr(sys, "frozen", False):
        try:
            import os
            devnull = os.open(os.devnull, os.O_WRONLY)
            os.dup2(devnull, sys.stdout.fileno())
            os.dup2(devnull, sys.stderr.fileno())
        except Exception:
            pass

    from gui import App
    app = App()
    app.mainloop()


if __name__ == "__main__":
    main()
