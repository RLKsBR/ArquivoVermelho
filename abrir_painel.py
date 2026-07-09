"""Open the Arquivo Vermelho local panel without using PowerShell."""

from __future__ import annotations

import http.server
import base64
from datetime import datetime, timezone
import json
import threading
import tkinter as tk
import webbrowser
from pathlib import Path
from tkinter import messagebox
from urllib.parse import parse_qs, urlparse


ROOT = Path(__file__).resolve().parent
PANEL_PATH = "/admin-local/"


class PanelRequestHandler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs) -> None:
        super().__init__(*args, directory=str(ROOT), **kwargs)

    def do_GET(self) -> None:
        request = urlparse(self.path)
        if request.path == "/api/read":
            values = parse_qs(request.query)
            try:
                self._send_text(self._site_path(values.get("path", [""])[0]).read_text(encoding="utf-8"))
            except (OSError, ValueError) as error:
                self.send_error(400, str(error))
            return
        if request.path == "/api/analytics":
            self._send_json(self._analytics())
            return
        super().do_GET()

    def do_POST(self) -> None:
        if self.path not in {"/api/write-text", "/api/write-binary"}:
            self.send_error(404, "Rota nao encontrada.")
            return

        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length <= 0 or length > 50 * 1024 * 1024:
                raise ValueError("Arquivo invalido ou maior que 50 MB.")
            payload = json.loads(self.rfile.read(length).decode("utf-8"))
            path = self._site_path(str(payload.get("path", "")))
            path.parent.mkdir(parents=True, exist_ok=True)

            if self.path == "/api/write-text":
                path.write_text(str(payload.get("text", "")), encoding="utf-8", newline="")
            else:
                content = str(payload.get("content", ""))
                path.write_bytes(base64.b64decode(content, validate=True))
        except (OSError, ValueError, json.JSONDecodeError) as error:
            self.send_error(400, str(error))
            return

        self._send_text("OK")

    def _site_path(self, value: str) -> Path:
        if not value or Path(value).is_absolute():
            raise ValueError("Caminho invalido.")
        path = (ROOT / value).resolve()
        if ROOT not in path.parents:
            raise ValueError("Caminho fora da pasta do site.")
        return path

    def _send_text(self, text: str) -> None:
        body = text.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _send_json(self, data: dict) -> None:
        body = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _analytics(self) -> dict:
        downloads = ROOT / "downloads"
        groups = {
            "a-hora-vermelha": {"label": "A Hora Vermelha", "files": 0, "bytes": 0},
            "cronicas": {"label": "Crônicas", "files": 0, "bytes": 0},
            "checkpoint-zumbi": {"label": "Checkpoint Zumbi", "files": 0, "bytes": 0},
            "o-ultimo-dia": {"label": "O Último Dia - Original", "files": 0, "bytes": 0},
            "o-ultimo-dia-portugues": {"label": "O Último Dia - 100% português", "files": 0, "bytes": 0},
        }
        files = []

        for pdf in downloads.rglob("*.pdf"):
            relative = pdf.relative_to(ROOT).as_posix()
            stat = pdf.stat()
            parts = pdf.relative_to(downloads).parts
            group_key = parts[0] if parts else ""
            if group_key == "o-ultimo-dia" and len(parts) > 1 and parts[1] == "portugues":
                group_key = "o-ultimo-dia-portugues"
            if group_key not in groups:
                continue
            groups[group_key]["files"] += 1
            groups[group_key]["bytes"] += stat.st_size
            files.append(
                {
                    "path": relative,
                    "name": pdf.name,
                    "bytes": stat.st_size,
                    "updated": datetime.fromtimestamp(stat.st_mtime, timezone.utc).isoformat(),
                }
            )

        files.sort(key=lambda item: item["updated"], reverse=True)
        return {
            "total_files": len(files),
            "total_bytes": sum(item["bytes"] for item in files),
            "groups": list(groups.values()),
            "recent": files[:8],
        }

    def log_message(self, format: str, *args) -> None:
        return


class LocalHttpServer(http.server.ThreadingHTTPServer):
    allow_reuse_address = True


class LocalServer:
    def __init__(self) -> None:
        self.server: LocalHttpServer | None = None
        self.url: str | None = None

    def start(self) -> str:
        if self.server and self.url:
            return self.url

        for port in range(8080, 8091):
            try:
                self.server = LocalHttpServer(("127.0.0.1", port), PanelRequestHandler)
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
