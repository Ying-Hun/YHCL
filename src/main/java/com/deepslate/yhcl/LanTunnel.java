package com.deepslate.yhcl;

import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * 直连联机核心：纯 JDK 实现的 UPnP IGD 端口映射 + 本机地址发现。
 * 不依赖任何外部程序/下载：SSDP 发现网关 → SOAP 调用 WANIPConnection 服务。
 * 没有 UPnP 时退化为「IPv6 / 局域网直连」地址展示。
 */
public final class LanTunnel {

    private String controlUrl;      // WANIPConnection 控制地址
    private String serviceType;     // 服务类型（含版本）
    private String externalIp;      // 映射成功后网关的公网 IP
    private int mappedPort = -1;

    /* ---------------- 地址发现 ---------------- */

    /** 本机局域网 IPv4（192.168/10/172.16…），找不到返回空串 */
    public static String localIpv4() {
        try {
            Enumeration<NetworkInterface> nifs = NetworkInterface.getNetworkInterfaces();
            while (nifs.hasMoreElements()) {
                NetworkInterface ni = nifs.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet6Address) continue;
                    if (a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                    if (a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return "";
    }

    /** 本机公网 IPv6（跳过 fe80 链路本地与 fc/fd ULA），找不到返回空串 */
    public static String localIpv6() {
        try {
            Enumeration<NetworkInterface> nifs = NetworkInterface.getNetworkInterfaces();
            while (nifs.hasMoreElements()) {
                NetworkInterface ni = nifs.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (!(a instanceof Inet6Address)) continue;
                    if (a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                    byte[] b = a.getAddress();
                    if ((b[0] & 0xfe) == 0xfc) continue;   // fc00::/7 ULA，外网连不进来
                    String s = a.getHostAddress();
                    int pct = s.indexOf('%');
                    return pct > 0 ? s.substring(0, pct) : s;
                }
            }
        } catch (Exception ignored) { }
        return "";
    }

    /* ---------------- UPnP 发现 ---------------- */

    /** SSDP 搜索 IGD 网关并解析控制地址。找到返回 true。 */
    public boolean discover() {
        controlUrl = null;
        serviceType = null;
        List<String> locations = new ArrayList<>();
        String[] sts = {
                "urn:schemas-upnp-org:device:InternetGatewayDevice:1",
                "urn:schemas-upnp-org:service:WANIPConnection:1",
                "urn:schemas-upnp-org:service:WANPPPConnection:1",
                "upnp:rootdevice"
        };
        for (String st : sts) {
            try (DatagramSocket sock = new DatagramSocket()) {
                sock.setSoTimeout(1500);
                String msg = "M-SEARCH * HTTP/1.1\r\n"
                        + "HOST: 239.255.255.250:1900\r\n"
                        + "MAN: \"ssdp:discover\"\r\n"
                        + "MX: 2\r\n"
                        + "ST: " + st + "\r\n\r\n";
                byte[] data = msg.getBytes(StandardCharsets.US_ASCII);
                sock.send(new DatagramPacket(data, data.length,
                        InetAddress.getByName("239.255.255.250"), 1900));
                long end = System.currentTimeMillis() + 1500;
                byte[] buf = new byte[4096];
                while (System.currentTimeMillis() < end) {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    sock.receive(pkt);
                    String resp = new String(pkt.getData(), 0, pkt.getLength(), StandardCharsets.US_ASCII);
                    for (String line : resp.split("\r\n")) {
                        if (line.toLowerCase().startsWith("location:")) {
                            String loc = line.substring(9).trim();
                            if (!locations.contains(loc)) locations.add(loc);
                        }
                    }
                }
            } catch (Exception ignored) { }
            if (!locations.isEmpty()) break;
        }
        for (String loc : locations) {
            if (parseDevice(loc)) return true;
        }
        return false;
    }

    /** 抓取设备描述 XML，找 WANIPConnection/WANPPPConnection 的控制 URL */
    private boolean parseDevice(String location) {
        try {
            Document doc = fetchXml(location);
            NodeList types = doc.getElementsByTagName("serviceType");
            NodeList ctrls = doc.getElementsByTagName("controlURL");
            for (int i = 0; i < types.getLength(); i++) {
                String t = types.item(i).getTextContent().trim();
                if (t.contains("WANIPConnection") || t.contains("WANPPPConnection")) {
                    serviceType = t;
                    String c = ctrls.item(i).getTextContent().trim();
                    URI base = URI.create(location);
                    controlUrl = base.resolve(c).toString();
                    return true;
                }
            }
        } catch (Exception ignored) { }
        return false;
    }

    /* ---------------- SOAP 调用 ---------------- */

    /** 查询网关公网 IP */
    public String getExternalIp() {
        if (controlUrl == null) return "";
        try {
            String body = soap("GetExternalIPAddress", "");
            int i = body.indexOf("<NewExternalIPAddress>");
            if (i > 0) {
                int j = body.indexOf("</NewExternalIPAddress>", i);
                externalIp = body.substring(i + 22, j).trim();
                return externalIp;
            }
        } catch (Exception ignored) { }
        return "";
    }

    /** 把本机端口映射到公网（TCP）。成功返回公网 IP，失败抛异常。 */
    public String addMapping(int port) throws Exception {
        if (controlUrl == null) throw new Exception("未发现支持 UPnP 的路由器网关");
        String lan = localIpv4();
        if (lan.isEmpty()) throw new Exception("未检测到局域网 IPv4 地址");
        String args = "<NewRemoteHost></NewRemoteHost>"
                + "<NewExternalPort>" + port + "</NewExternalPort>"
                + "<NewProtocol>TCP</NewProtocol>"
                + "<NewInternalPort>" + port + "</NewInternalPort>"
                + "<NewInternalClient>" + lan + "</NewInternalClient>"
                + "<NewEnabled>1</NewEnabled>"
                + "<NewPortMappingDescription>YHCL Minecraft</NewPortMappingDescription>"
                + "<NewLeaseDuration>0</NewLeaseDuration>";
        soap("AddPortMapping", args);
        mappedPort = port;
        String ip = getExternalIp();
        if (ip.isEmpty()) ip = externalIp == null ? "" : externalIp;
        if (ip.isEmpty()) throw new Exception("映射已创建，但网关未返回公网 IP");
        return ip;
    }

    /** 删除映射（退出时调用，尽力而为） */
    public void removeMapping() {
        if (controlUrl == null || mappedPort <= 0) return;
        try {
            soap("DeletePortMapping",
                    "<NewRemoteHost></NewRemoteHost>"
                            + "<NewExternalPort>" + mappedPort + "</NewExternalPort>"
                            + "<NewProtocol>TCP</NewProtocol>");
        } catch (Exception ignored) { }
        mappedPort = -1;
    }

    public boolean ready() { return controlUrl != null; }
    public String externalIp() { return externalIp == null ? "" : externalIp; }

    private String soap(String action, String args) throws Exception {
        String envelope = "<?xml version=\"1.0\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\""
                + " s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                + "<s:Body><u:" + action + " xmlns:u=\"" + serviceType + "\">"
                + args
                + "</u:" + action + "></s:Body></s:Envelope>";
        HttpURLConnection conn = (HttpURLConnection) URI.create(controlUrl).toURL().openConnection();
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(5000);
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
        conn.setRequestProperty("SOAPAction", "\"" + serviceType + "#" + action + "\"");
        conn.getOutputStream().write(envelope.getBytes(StandardCharsets.UTF_8));
        int code = conn.getResponseCode();
        java.io.InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (in == null) throw new Exception("网关无响应（HTTP " + code + "）");
        String resp = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        if (code >= 400) throw new Exception("网关拒绝 " + action + "（HTTP " + code + "）");
        return resp;
    }

    private static Document fetchXml(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(5000);
        try (java.io.InputStream in = conn.getInputStream()) {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            // 防 XXE：网关描述是内网设备返回的 XML，禁用外部实体
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setExpandEntityReferences(false);
            return f.newDocumentBuilder().parse(in);
        }
    }
}
