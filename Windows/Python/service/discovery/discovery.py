"""
DeskTwin Discovery (mDNS)
--------------------------
بدل ما المستخدم يدوّر على IP الكمبيوتر يدويًا، الكمبيوتر بيعلن عن نفسه
على الشبكة المحلية باسم ثابت (زي "DeskTwin-PC._desktwin._tcp.local.").
الموبايل بيدور بالاسم ده ويلاقي IP والبورت تلقائيًا.

هذا الملف فيه جزئين:
    1. DeskTwinAdvertiser -> بيشتغل على الكمبيوتر، بيعلن عن الخدمة.
    2. discover_desktwin_services() -> مثال لكود البحث (اللي هيتماثل
       جزء منه لاحقًا في تطبيق الموبايل، هنا كمرجع/اختبار).
"""

import os
import sys
import socket
import time
import ipaddress

sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from zeroconf import ServiceInfo, Zeroconf, ServiceBrowser, ServiceListener
import ifaddr

SERVICE_TYPE = "_desktwin._tcp.local."


def _get_local_ips() -> list[str]:
    """يرجع عناوين IPv4 الفعلية لكل واجهات الشبكة، بلا اعتماد على الإنترنت."""
    try:
        adapters = ifaddr.get_adapters()
    except OSError as exc:
        raise RuntimeError("تعذر قراءة عناوين واجهات الشبكة") from exc

    addresses = set()
    for adapter in adapters:
        for interface_address in adapter.ips:
            if not interface_address.is_IPv4:
                continue
            try:
                address = ipaddress.ip_address(interface_address.ip)
            except ValueError:
                continue
            # Link-local (169.254/16) addresses are not routable between the
            # phone and PC. Advertising them can make Android select a dead
            # endpoint even while a valid Wi-Fi/Ethernet address is present.
            if address.is_loopback or address.is_unspecified or address.is_multicast or address.is_link_local:
                continue
            addresses.add(str(address))

    if not addresses:
        raise RuntimeError("لم يتم العثور على عنوان IPv4 صالح للإعلان على الشبكة")

    return sorted(addresses, key=lambda value: (ipaddress.ip_address(value).is_link_local, value))


class DeskTwinAdvertiser:
    """
    بيشتغل جنب الـ API على الكمبيوتر. بيعلن: "أنا هنا، اسمي كذا، شغال على IP:PORT كذا."
    """

    def __init__(self, port: int = 8420, computer_name: str = None):
        self.port = port
        self.computer_name = computer_name or socket.gethostname() or "DeskTwin-PC"
        self.zeroconf = None
        self.service_info = None
        self.local_ips = []

    def start(self):
        self.local_ips = _get_local_ips()
        instance_name = f"{self.computer_name}.{SERVICE_TYPE}"

        self.service_info = ServiceInfo(
            SERVICE_TYPE,
            instance_name,
            addresses=[socket.inet_aton(ip) for ip in self.local_ips],
            port=self.port,
            properties={
                "version": "0.1",
                "name": self.computer_name,
            },
        )

        self.zeroconf = Zeroconf()
        self.zeroconf.register_service(self.service_info)

    def stop(self):
        if self.zeroconf and self.service_info:
            self.zeroconf.unregister_service(self.service_info)
            self.zeroconf.close()
            self.zeroconf = None
            self.service_info = None


# ---------- كود البحث (مرجع - ده اللي الموبايل هيعمل مثله) ----------

class _CollectingListener(ServiceListener):
    def __init__(self):
        self.found = []

    def add_service(self, zc, type_, name):
        info = zc.get_service_info(type_, name)
        if info:
            ip = socket.inet_ntoa(info.addresses[0])
            self.found.append({
                "name": info.properties.get(b"name", b"").decode("utf-8", errors="ignore"),
                "ip": ip,
                "port": info.port,
            })

    def update_service(self, zc, type_, name):
        pass

    def remove_service(self, zc, type_, name):
        pass


def discover_desktwin_services(timeout_seconds: float = 3.0) -> list:
    """
    بيدور على أي DeskTwin Service شغال على الشبكة المحلية دلوقتي.
    بيرجع قائمة بكل الأجهزة اللي لقاها: [{"name": ..., "ip": ..., "port": ...}, ...]
    """
    zeroconf = Zeroconf()
    listener = _CollectingListener()
    ServiceBrowser(zeroconf, SERVICE_TYPE, listener)

    time.sleep(timeout_seconds)  # وقت كافي عشان الردود توصل من الشبكة
    zeroconf.close()

    return listener.found


if __name__ == "__main__":
    advertiser = DeskTwinAdvertiser(port=8420, computer_name="MyPC")
    advertiser.start()
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        advertiser.stop()
