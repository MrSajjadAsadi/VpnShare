"""
VPN Bridge - Windows Companion Client
Connect your PC to Android Phone's VPN via Hotspot or USB.
"""

import sys
import os
import subprocess
import threading
import time
import winreg
import ctypes
from ctypes import wintypes
import tkinter as tk
from tkinter import ttk, messagebox
import urllib.request

# WinINet constants for instant proxy notification
INTERNET_OPTION_SETTINGS_CHANGED = 39
INTERNET_OPTION_REFRESH = 37

def notify_system_proxy_changed():
    """Forces Windows and all running apps to refresh proxy settings immediately."""
    try:
        wininet = ctypes.windll.wininet
        wininet.InternetSetOptionW(0, INTERNET_OPTION_SETTINGS_CHANGED, 0, 0)
        wininet.InternetSetOptionW(0, INTERNET_OPTION_REFRESH, 0, 0)
    except Exception as e:
        print(f"Error notifying WinINet: {e}")

def get_default_gateway():
    """Attempts to discover phone's hotspot IP from the default route."""
    try:
        cmd = "powershell -Command \"(Get-NetRoute -DestinationPrefix '0.0.0.0/0' | Sort-Object RouteMetric | Select-Object -First 1).NextHop\""
        out = subprocess.check_output(cmd, shell=True, text=True, stderr=subprocess.DEVNULL).strip()
        if out and out != "0.0.0.0" and len(out.split(".")) == 4:
            return out
    except Exception:
        pass
    return "192.168.43.1"

class VpnBridgeApp(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("VPN Share Bridge - Windows Client")
        self.geometry("520x620")
        self.resizable(False, False)
        self.configure(bg="#0F172A")

        self.is_connected = False
        self.current_ip = tk.StringVar(value=get_default_gateway())
        self.current_http_port = tk.StringVar(value="8080")
        self.current_socks_port = tk.StringVar(value="1080")
        self.status_var = tk.StringVar(value="غیرمتصل (Disconnected)")
        self.ping_var = tk.StringVar(value="")

        self.setup_ui()
        self.check_initial_proxy_state()

    def setup_ui(self):
        style = ttk.Style(self)
        style.theme_use("clam")

        # Container
        main_frame = tk.Frame(self, bg="#0F172A", padx=24, pady=20)
        main_frame.pack(fill="both", expand=True)

        # Header Title
        title_lbl = tk.Label(
            main_frame, text="VPN Share Bridge",
            font=("Segoe UI", 18, "bold"), fg="#FFFFFF", bg="#0F172A"
        )
        title_lbl.pack(anchor="w")

        subtitle_lbl = tk.Label(
            main_frame, text="اتصال اینترنت لپ‌تاپ به فیلترشکن گوشی (Hotspot & USB)",
            font=("Segoe UI", 10), fg="#94A3B8", bg="#0F172A"
        )
        subtitle_lbl.pack(anchor="w", pady=(0, 16))

        # Status Card
        status_card = tk.Frame(main_frame, bg="#1E293B", padx=16, pady=16, relief="flat")
        status_card.pack(fill="x", pady=(0, 16))

        self.status_lbl = tk.Label(
            status_card, textvariable=self.status_var,
            font=("Segoe UI", 12, "bold"), fg="#E2E8F0", bg="#334155", padx=12, pady=6
        )
        self.status_lbl.pack()

        # Connect / Disconnect Big Button
        self.toggle_btn = tk.Button(
            status_card, text="اتصال به فیلترشکن گوشی",
            font=("Segoe UI", 12, "bold"), fg="#FFFFFF", bg="#2563EB",
            activebackground="#1D4ED8", activeforeground="#FFFFFF",
            relief="flat", cursor="hand2", padx=20, pady=10,
            command=self.toggle_proxy
        )
        self.toggle_btn.pack(pady=(14, 0))

        # Settings Card
        settings_card = tk.Frame(main_frame, bg="#1E293B", padx=16, pady=16)
        settings_card.pack(fill="x", pady=(0, 16))

        cfg_lbl = tk.Label(
            settings_card, text="تنظیمات پروکسی گوشی (Phone Proxy Info)",
            font=("Segoe UI", 11, "bold"), fg="#38BDF8", bg="#1E293B"
        )
        cfg_lbl.pack(anchor="w", pady=(0, 10))

        # IP Row
        ip_row = tk.Frame(settings_card, bg="#1E293B")
        ip_row.pack(fill="x", pady=4)
        tk.Label(ip_row, text="آدرس IP گوشی:", font=("Segoe UI", 10), fg="#94A3B8", bg="#1E293B").pack(side="left")
        ip_entry = tk.Entry(ip_row, textvariable=self.current_ip, font=("Consolas", 11), width=16, bg="#0F172A", fg="#34D399", insertbackground="white", relief="flat")
        ip_entry.pack(side="right")

        auto_ip_btn = tk.Button(
            ip_row, text="شناسایی خودکار IP", font=("Segoe UI", 8),
            fg="#94A3B8", bg="#334155", relief="flat", cursor="hand2",
            command=self.auto_detect_ip
        )
        auto_ip_btn.pack(side="right", padx=(0, 8))

        # HTTP Port Row
        port_row = tk.Frame(settings_card, bg="#1E293B")
        port_row.pack(fill="x", pady=4)
        tk.Label(port_row, text="پورت HTTP / HTTPS:", font=("Segoe UI", 10), fg="#94A3B8", bg="#1E293B").pack(side="left")
        port_entry = tk.Entry(port_row, textvariable=self.current_http_port, font=("Consolas", 11), width=8, bg="#0F172A", fg="#FFFFFF", insertbackground="white", relief="flat")
        port_entry.pack(side="right")

        # SOCKS Port Row
        socks_row = tk.Frame(settings_card, bg="#1E293B")
        socks_row.pack(fill="x", pady=4)
        tk.Label(socks_row, text="پورت SOCKS5:", font=("Segoe UI", 10), fg="#94A3B8", bg="#1E293B").pack(side="left")
        socks_entry = tk.Entry(socks_row, textvariable=self.current_socks_port, font=("Consolas", 11), width=8, bg="#0F172A", fg="#FFFFFF", insertbackground="white", relief="flat")
        socks_entry.pack(side="right")

        # Connection Test & Tools Card
        tools_card = tk.Frame(main_frame, bg="#1E293B", padx=16, pady=14)
        tools_card.pack(fill="x", pady=(0, 16))

        tools_header = tk.Frame(tools_card, bg="#1E293B")
        tools_header.pack(fill="x")

        test_btn = tk.Button(
            tools_header, text="تست اینترنت جهانی (Ping)", font=("Segoe UI", 9, "bold"),
            fg="#FFFFFF", bg="#475569", relief="flat", cursor="hand2", padx=10, pady=4,
            command=self.test_connection
        )
        test_btn.pack(side="left")

        self.ping_lbl = tk.Label(tools_header, textvariable=self.ping_var, font=("Segoe UI", 9), fg="#38BDF8", bg="#1E293B")
        self.ping_lbl.pack(side="right")

        # USB Mode Button
        usb_btn = tk.Button(
            tools_card, text="🔌 اتصال با کابل USB (ADB Mode)", font=("Segoe UI", 9),
            fg="#F1F5F9", bg="#334155", relief="flat", cursor="hand2", padx=8, pady=4,
            command=self.connect_via_usb
        )
        usb_btn.pack(fill="x", pady=(10, 0))

        # Instructions Footer
        info_lbl = tk.Label(
            main_frame,
            text="نکته: برای اتصال، گوشی را هات‌اسپات کرده و وی‌پی‌ان آن را روشن کنید.\nسپس با زدن دکمه اتصال، تمام ترافیک ویندوز از فیلترشکن گوشی رد خواهد شد.",
            font=("Segoe UI", 9), fg="#64748B", bg="#0F172A", justify="center"
        )
        info_lbl.pack(side="bottom")

    def auto_detect_ip(self):
        gw = get_default_gateway()
        self.current_ip.set(gw)
        messagebox.showinfo("شناسایی IP", f"آدرس گیت‌وی شناسایی شده: {gw}")

    def check_initial_proxy_state(self):
        try:
            reg_key = winreg.OpenKey(
                winreg.HKEY_CURRENT_USER,
                r"Software\Microsoft\Windows\CurrentVersion\Internet Settings",
                0, winreg.KEY_READ
            )
            proxy_enable, _ = winreg.QueryValueEx(reg_key, "ProxyEnable")
            winreg.CloseKey(reg_key)
            if proxy_enable == 1:
                self.set_connected_state(True)
        except Exception:
            pass

    def toggle_proxy(self):
        if self.is_connected:
            self.disable_windows_proxy()
        else:
            self.enable_windows_proxy()

    def enable_windows_proxy(self):
        ip = self.current_ip.get().strip()
        http_port = self.current_http_port.get().strip()
        socks_port = self.current_socks_port.get().strip()

        if not ip or not http_port:
            messagebox.showerror("خطا", "لطفاً آدرس IP و پورت را وارد کنید.")
            return

        proxy_str = f"http={ip}:{http_port};https={ip}:{http_port}"
        if socks_port:
            proxy_str += f";socks={ip}:{socks_port}"

        try:
            reg_key = winreg.OpenKey(
                winreg.HKEY_CURRENT_USER,
                r"Software\Microsoft\Windows\CurrentVersion\Internet Settings",
                0, winreg.KEY_WRITE
            )
            winreg.SetValueEx(reg_key, "ProxyEnable", 0, winreg.REG_DWORD, 1)
            winreg.SetValueEx(reg_key, "ProxyServer", 0, winreg.REG_SZ, proxy_str)
            winreg.SetValueEx(reg_key, "ProxyOverride", 0, winreg.REG_SZ, "<local>;localhost;127.0.0.1")
            winreg.CloseKey(reg_key)

            notify_system_proxy_changed()
            self.set_connected_state(True)
        except Exception as e:
            messagebox.showerror("خطا", f"خطا در فعال‌سازی پروکسی ویندوز:\n{e}")

    def disable_windows_proxy(self):
        try:
            reg_key = winreg.OpenKey(
                winreg.HKEY_CURRENT_USER,
                r"Software\Microsoft\Windows\CurrentVersion\Internet Settings",
                0, winreg.KEY_WRITE
            )
            winreg.SetValueEx(reg_key, "ProxyEnable", 0, winreg.REG_DWORD, 0)
            winreg.CloseKey(reg_key)

            notify_system_proxy_changed()
            self.set_connected_state(False)
        except Exception as e:
            messagebox.showerror("خطا", f"خطا در غیرفعال‌سازی پروکسی ویندوز:\n{e}")

    def set_connected_state(self, connected: bool):
        self.is_connected = connected
        if connected:
            self.status_var.set("✔ متصل به فیلترشکن گوشی (Connected)")
            self.status_lbl.configure(bg="#065F46", fg="#34D399")
            self.toggle_btn.configure(text="قطع اتصال (Disconnect)", bg="#DC2626", activebackground="#B91C1C")
        else:
            self.status_var.set("غیرمتصل (Disconnected)")
            self.status_lbl.configure(bg="#334155", fg="#CBD5E1")
            self.toggle_btn.configure(text="اتصال به فیلترشکن گوشی", bg="#2563EB", activebackground="#1D4ED8")
            self.ping_var.set("")

    def test_connection(self):
        self.ping_var.set("در حال بررسی...")
        self.update_idletasks()

        def worker():
            try:
                start = time.time()
                # Test connection via proxy (or system default)
                opener = urllib.request.build_opener()
                req = urllib.request.Request("https://www.google.com/generate_204", headers={"User-Agent": "Mozilla/5.0"})
                with opener.open(req, timeout=5) as resp:
                    if resp.status == 204 or resp.status == 200:
                        elapsed = int((time.time() - start) * 1000)
                        self.ping_var.set(f"اینترنت آزاد وصل است: {elapsed} ms")
                        return
                self.ping_var.set("عدم دریافت پاسخ مناسب")
            except Exception as e:
                self.ping_var.set("خطای اتصال به اینترنت")

        threading.Thread(target=worker, daemon=True).start()

    def connect_via_usb(self):
        """Sets up ADB port-forwarding over USB cable."""
        def worker():
            try:
                # Check ADB command
                subprocess.check_call("adb forward tcp:8080 tcp:8080", shell=True, stderr=subprocess.DEVNULL)
                subprocess.check_call("adb forward tcp:1080 tcp:1080", shell=True, stderr=subprocess.DEVNULL)
                self.current_ip.set("127.0.0.1")
                self.enable_windows_proxy()
                messagebox.showinfo("حالت کابل USB", "اتصال از طریق کابل USB با موفقیت برقرار شد!\nIP روی 127.0.0.1 تنظیم شد.")
            except Exception:
                messagebox.showwarning(
                    "ADB پیدا نشد",
                    "برای استفاده از کابل USB، قابلیت USB Debugging در گوشی و ADB در ویندوز باید فعال باشد.\nمی‌توانید به سادگی از حالت هات‌اسپات Wi-Fi استفاده کنید."
                )
        threading.Thread(target=worker, daemon=True).start()

if __name__ == "__main__":
    app = VpnBridgeApp()
    app.mainloop()
