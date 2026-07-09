"""Open the Arquivo Vermelho local panel without using PowerShell."""

from __future__ import annotations

import http.server
import socketserver
import threading
import tkinter as tk
import webbrowser
from pathlib import Path
from tkinter import messagebox


ROOT = Path(__file__).resolve().parent
PANEL_PATH = "/admin-local/"


class LocalServer:
    def __init__(self) -> None:
        self.server: socketserver.TCPServer | None = None
        self.url: str | None = None

    def start(self) -> str:
        if self.server and self.url:
            return self.url

        handler = lambda *args, **kwargs: http.server.SimpleHTTPRequestHandler(
            *args, directory=str(ROOT), **kwargs
        )

        for port in range(8080, 8091):
            try:
                self.server = socketserver.TCPServer(("127.0.0.1", port), handler)
                break
            except OSError:
                continue
        else:
            raise RuntimeError("Nao foi possivel encontrar uma porta local livre.")

        self.url = f"http://127.0.0.1:{self.server.server_address[1]}{PANEL_PATH}"
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        return self.url

    def stop(self) -> None:
        if self.server:
            self.server.shutdown()
            self.server.server_close()
        self.server = None
        self.url = None


def main() -> None:
    if not (ROOT / "admin-local" / "index.html").is_file():
        messagebox.showerror(
            "Painel nao encontrado",
            "A pasta admin-local nao foi encontrada ao lado deste arquivo.",
        )
        return

    server = LocalServer()
    app = tk.Tk()
    app.title("Painel local - Arquivo Vermelho")
    app.resizable(False, False)
    app.configure(bg="#120d0c")

    frame = tk.Frame(app, bg="#120d0c", padx=28, pady=24)
    frame.pack()

    tk.Label(
        frame,
        text="Arquivo Vermelho",
        font=("Georgia", 20, "bold"),
        fg="#fff3e6",
        bg="#120d0c",
    ).pack(anchor="w")
    tk.Label(
        frame,
        text="Painel local de PDFs",
        font=("Segoe UI", 11, "bold"),
        fg="#d4a74d",
        bg="#120d0c",
    ).pack(anchor="w", pady=(4, 10))

    status = tk.StringVar(value="Preparando o painel local...")
    tk.Label(
        frame,
        textvariable=status,
        justify="left",
        wraplength=390,
        font=("Segoe UI", 10),
        fg="#d9cbc4",
        bg="#120d0c",
    ).pack(anchor="w", pady=(0, 18))

    def open_panel() -> None:
        try:
            url = server.start()
        except RuntimeError as error:
            messagebox.showerror("Erro ao abrir o painel", str(error))
            return
        status.set(
            "Painel aberto no navegador. Mantenha esta janela aberta enquanto estiver publicando PDFs."
        )
        webbrowser.open(url)

    buttons = tk.Frame(frame, bg="#120d0c")
    buttons.pack(anchor="w")
    tk.Button(
        buttons,
        text="Abrir painel",
        command=open_panel,
        font=("Segoe UI", 10, "bold"),
        bg="#c8323d",
        activebackground="#d9434e",
        fg="#fff3e6",
        activeforeground="#fff3e6",
        relief="flat",
        padx=16,
        pady=9,
        cursor="hand2",
    ).pack(side="left")
    tk.Button(
        buttons,
        text="Fechar",
        command=lambda: (server.stop(), app.destroy()),
        font=("Segoe UI", 10),
        bg="#211715",
        activebackground="#2b1d1a",
        fg="#fff3e6",
        activeforeground="#fff3e6",
        relief="flat",
        padx=16,
        pady=9,
        cursor="hand2",
    ).pack(side="left", padx=(10, 0))

    def close() -> None:
        server.stop()
        app.destroy()

    app.protocol("WM_DELETE_WINDOW", close)
    open_panel()
    app.mainloop()


if __name__ == "__main__":
    main()
