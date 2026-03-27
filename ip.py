import socket
import dns.resolver

def check_dns(domain):
    print(f"--- 正在诊断域名: {domain} ---")
    
    # 1. 检查系统当前解析结果 (受 Clash TUN/系统代理影响)
    try:
        system_ip = socket.gethostbyname(domain)
        print(f"[系统解析] IP: {system_ip}")
        if system_ip.startswith("198.18"):
            print("  ⚠️ 警告: 该 IP 属于 Clash Fake-IP 范围，直连规则可能未在 DNS 层面生效。")
        else:
            print("  ✅ 成功: 获取到了真实 IP。")
    except Exception as e:
        print(f"  ❌ 系统解析失败: {e}")

    # 2. 强制使用公网 DNS 解析 (绕过本地代理层)
    try:
        resolver = dns.resolver.Resolver()
        resolver.nameservers = ['223.5.5.5'] # 阿里云公共DNS
        answer = resolver.resolve(domain, 'A')
        real_ips = [data.address for data in answer]
        print(f"[公网解析] 阿里 DNS 返回的真实 IP: {real_ips}")
    except Exception as e:
        print(f"  ❌ 强制公网解析失败 (可能被防火墙拦截): {e}")
    print("\n")

if __name__ == "__main__":
    # 填入你图片中报错的域名
    target_domains = ["www.ponychat.top", "vixa11815507.vicp.fun"]
    for d in target_domains:
        check_dns(d)