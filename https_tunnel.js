const net = require("net");
const http = require("http");

// ONE PUBLIC PORT: control page + unchanged raw TCP relay
const PORT = 3000;
let TARGET_PORT = 8090;
let TARGET_IPV6 = "2001:ee0:29c:11cd:ebc7:c6e7:20b1:ca5a";
let connectionCount = 0;

// =========================
// CONTROL PAGE
// Control UI: http://YOUR_SERVER:3000/control
// /set and /status are reserved for the control page.
// All other traffic, including modem HTTP/HTTPS, is relayed as raw TCP.
// =========================
const control = http.createServer((req, res) => {
    res.setHeader("Access-Control-Allow-Origin", "*");
    res.setHeader("Cache-Control", "no-store");

    const pathname = new URL(req.url, "http://localhost").pathname;

    if (req.method === "GET" && pathname === "/control") {
        res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
        res.end(`<!DOCTYPE html>
<html lang="vi">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="theme-color" content="#073b9b">
<title>VNPT - TCP TUNNEL CONTROL</title>
<style>
:root{font-family:Arial,Helvetica,sans-serif;color:#102b63;background:#fff}
*{box-sizing:border-box}
body{margin:0;padding:18px;background:#fff}
.shell{width:100%;max-width:1100px;margin:12px auto;border:2px solid #073b9b;background:#fff}
.hero{min-height:82px;padding:18px 24px;background:#073b9b;color:#fff;display:flex;align-items:center;gap:18px}
.brand{font-size:24px;font-weight:800;white-space:nowrap;border-right:2px solid rgba(255,255,255,.65);padding-right:18px}
.hero h1{font-size:clamp(20px,3vw,30px);margin:0;letter-spacing:.3px}
.content{padding:26px 24px}
.options{display:grid;gap:18px;padding:4px 0 24px;border-bottom:2px solid #073b9b}
.option{display:grid;grid-template-columns:38px minmax(220px,310px) minmax(0,1fr) 58px;gap:12px;align-items:center}
.radio{width:24px;height:24px;justify-self:center;accent-color:#075bbb;cursor:pointer}
.choice{height:66px;border:1px solid #075bbb;background:#dceeff;color:#102b63;display:flex;align-items:center;justify-content:center;gap:12px;font-size:clamp(17px,2.3vw,24px);font-weight:800;cursor:pointer;padding:8px;text-align:center}
.choice.proxy{background:#e4f6e9;border-color:#13833d}
.choice .symbol{font-size:30px;color:#075bbb}
.url{height:66px;min-width:0;border:1px solid #073b9b;display:flex;align-items:center;gap:12px;padding:8px 12px;color:#003fc2;font-size:clamp(13px,1.8vw,20px);font-weight:700;overflow-wrap:anywhere}
.url .url-icon{font-size:27px;flex:0 0 auto}
.copy{height:58px;border:1px solid #073b9b;background:#fff;color:#073b9b;font-size:27px;cursor:pointer}
.copy:active,.choice:active,.remote:active{background:#dceeff}
.remote-wrap{padding:26px 0 2px;text-align:center}
.remote{width:min(620px,90%);min-height:76px;border:0;background:#073b9b;color:#fff;font-size:clamp(21px,3vw,30px);font-weight:800;cursor:pointer;padding:14px 24px}
.message{text-align:center;min-height:20px;margin-top:12px;font-size:13px;color:#24518b}
@media(max-width:700px){body{padding:8px}.shell{margin:4px auto}.hero{padding:15px;gap:12px}.brand{font-size:18px;padding-right:12px}.content{padding:17px 12px}.option{grid-template-columns:30px minmax(0,1fr) 46px;gap:8px}.choice{grid-column:2;grid-row:1;height:auto;min-height:54px;font-size:16px}.url{grid-column:2;grid-row:2;height:auto;min-height:54px;font-size:12px;padding:8px}.copy{grid-column:3;grid-row:1 / span 2;height:50px;font-size:22px}.radio{grid-column:1;grid-row:1 / span 2}.options{gap:14px}.remote{width:100%;min-height:64px}}
</style>
</head>
<body>
<div class="shell">
<header class="hero"><div class="brand">◉ VNPT</div><h1>TCP TUNNEL CONTROL</h1></header>
<main class="content">
<section class="options" aria-label="Chọn kiểu kết nối">
  <div class="option">
    <input class="radio" type="radio" name="remoteMode" id="modeDirect" value="direct" checked>
    <label class="choice" for="modeDirect"><span class="symbol">🔗</span>Link Direct</label>
    <div class="url"><span class="url-icon">🌐</span><span id="directUrl">Đang đọc IPv6...</span></div>
    <button class="copy" type="button" title="Sao chép Link Direct" onclick="copyUrl('directUrl')">▢</button>
  </div>
  <div class="option">
    <input class="radio" type="radio" name="remoteMode" id="modeProxy" value="proxy">
    <label class="choice proxy" for="modeProxy"><span class="symbol">▣</span>Link Proxy</label>
    <div class="url"><span class="url-icon">▤</span><span id="proxyUrl">Đang đọc địa chỉ máy chủ...</span></div>
    <button class="copy" type="button" title="Sao chép Link Proxy" onclick="copyUrl('proxyUrl')">▢</button>
  </div>
</section>
<div class="remote-wrap"><button class="remote" type="button" onclick="openRemote()">⌁ &nbsp; REMOTE MODEM</button></div>
<div class="message" id="message" aria-live="polite"></div>
</main>
</div>
<script>
const directUrl=document.getElementById("directUrl");
const proxyUrl=document.getElementById("proxyUrl");
const message=document.getElementById("message");
function selectedMode(){return document.querySelector('input[name="remoteMode"]:checked')?.value||"direct";}
async function initialize(){
  const params=new URLSearchParams(window.location.search);
  const ipv6=(params.get("ipv6")||"").trim();
  const portText=(params.get("port")||"8090").trim();
  const port=Number(portText);
  const direct=ipv6 ? "https://["+ipv6+"]:"+port+"/" : "Chưa có IPv6 trong URL";
  const proxy="https://"+window.location.host+"/";
  directUrl.textContent=direct;
  proxyUrl.textContent=proxy;
  if(!ipv6){message.textContent="Thiếu tham số ipv6 trong URL điều khiển.";return;}
  if(!Number.isInteger(port)||port<1||port>65535){message.textContent="Port không hợp lệ.";return;}
  message.textContent="Đang tự động cập nhật IPv6 và Port...";
  try{
    const r=await fetch("/set",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({ipv6,port})});
    const d=await r.json();
    if(!r.ok)throw new Error(d.error||"Cập nhật thất bại");
    message.textContent="Đã cập nhật IPv6 và Port. Chọn Link Direct hoặc Link Proxy rồi nhấn REMOTE MODEM.";
  }catch(e){message.textContent="Lỗi cập nhật: "+e.message;}
}
function openRemote(){
  if(selectedMode()==="direct"){
    const url=directUrl.textContent;
    if(!url.startsWith("https://[")){message.textContent="Không có IPv6 hợp lệ để mở Link Direct.";return;}
    window.open(url,"_blank");
  }else{
    window.open(proxyUrl.textContent,"_blank");
  }
}
async function copyUrl(id){
  const value=document.getElementById(id).textContent;
  try{await navigator.clipboard.writeText(value);message.textContent="Đã sao chép đường dẫn.";}
  catch(e){message.textContent="Không thể sao chép tự động trên trình duyệt này.";}
}
initialize();
</script>
</body>
</html>`);
        return;
    }

    if (req.method === "GET" && pathname === "/status") {
        res.writeHead(200, { "Content-Type": "application/json; charset=utf-8" });
        res.end(JSON.stringify({
            status: "running",
            ipv6: TARGET_IPV6,
            port: TARGET_PORT,
            relayPort: PORT
        }));
        return;
    }

    if (req.method === "POST" && pathname === "/set") {
        let body = "";
        req.on("data", chunk => {
            body += chunk;
            if (body.length > 4096) req.destroy();
        });
        req.on("end", () => {
            try {
                const data = JSON.parse(body);
                const ipv6 = String(data.ipv6 || "").trim();
                const port = Number(data.port || 8090);
                if (net.isIP(ipv6) !== 6) {
                    res.writeHead(400, { "Content-Type": "application/json; charset=utf-8" });
                    res.end(JSON.stringify({ error: "INVALID_IPV6" }));
                    return;
                }
                if (!Number.isInteger(port) || port < 1 || port > 65535) {
                    res.writeHead(400, { "Content-Type": "application/json; charset=utf-8" });
                    res.end(JSON.stringify({ error: "INVALID_PORT" }));
                    return;
                }
                const oldIP = TARGET_IPV6;
                const oldPort = TARGET_PORT;
                TARGET_IPV6 = ipv6;
                TARGET_PORT = port;
                console.log("[IP UPDATED]");
                console.log("OLD:", oldIP);
                console.log("NEW:", TARGET_IPV6);
                console.log("PORT:", oldPort, "->", TARGET_PORT);
                res.writeHead(200, { "Content-Type": "application/json; charset=utf-8" });
                res.end(JSON.stringify({
                    status: "OK",
                    message: "TARGET UPDATED",
                    oldIPv6: oldIP,
                    newIPv6: TARGET_IPV6,
                    oldPort,
                    port: TARGET_PORT
                }));
            } catch (err) {
                res.writeHead(400, { "Content-Type": "application/json; charset=utf-8" });
                res.end(JSON.stringify({ error: "INVALID_REQUEST" }));
            }
        });
        return;
    }

    if (req.method === "GET" && pathname === "/favicon.ico") {
        res.writeHead(204);
        res.end();
        return;
    }

    res.writeHead(404);
    res.end("NOT_FOUND");
});

control.on("error", err => console.log("[CONTROL ERROR]", err.message));
control.on("clientError", (err, socket) => {
    console.log("[CONTROL CLIENT ERROR]", err.message);
    if (socket.writable) socket.end("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n");
});

// =========================
// ORIGINAL TCP RELAY LOGIC
// Same net.connect() + bidirectional pipe as _tunnel.js.
// No HTTP proxy, no HTTP/HTML rewriting of modem traffic.
// =========================
function relayTCP(client, firstData) {
    // Pause immediately so no bytes are lost while Android A is connecting.
    client.pause();
    if (firstData && firstData.length) client.unshift(firstData);

    const target = TARGET_IPV6;
    const id = ++connectionCount;

    console.log(`[CLIENT ${id}] ${client.remoteAddress}`);
    console.log(`[TARGET ${id}] [${target}]:${TARGET_PORT}`);

    const upstream = net.connect({
        host: target,
        port: TARGET_PORT,
        family: 6
    });

    upstream.on("connect", () => {
        console.log(`[CONNECTED ${id}] Android A`);
        client.pipe(upstream);
        upstream.pipe(client);
    });

    upstream.on("error", err => {
        console.log(`[UPSTREAM ERROR ${id}]`, err.message);
        client.destroy();
    });

    client.on("error", err => {
        console.log(`[CLIENT ERROR ${id}]`, err.message);
        upstream.destroy();
    });

    client.on("close", () => upstream.destroy());
    upstream.on("close", () => client.destroy());
}

// =========================
// ONE LISTENER / ONE PUBLIC PORT
// /control, /set, /status -> local control server
// EVERYTHING ELSE        -> unchanged raw TCP relay to Android A:8090
// =========================
const listener = net.createServer(client => {
    let firstData = Buffer.alloc(0);
    let decided = false;

    client.on("error", err => console.log("[INCOMING CLIENT ERROR]", err.message));

    function onData(chunk) {
        if (decided) return;
        firstData = Buffer.concat([firstData, chunk]);
        // HTTPS starts with a TLS handshake record (0x16 0x03).
        // Relay it immediately as raw TCP; do not wait for an HTTP request line.
        const isTLS = firstData.length >= 3 &&
            firstData[0] === 0x16 && firstData[1] === 0x03;

        if (isTLS) {
            decided = true;
            client.removeListener("data", onData);
            relayTCP(client, firstData);
            return;
        }

        const text = firstData.toString("latin1");
        const lineEnd = text.indexOf("\r\n");

        // Wait for a complete HTTP request line before classifying.
        if (lineEnd === -1 && firstData.length < 8192) return;

        const requestLine = lineEnd >= 0 ? text.slice(0, lineEnd) : "";
        const match = requestLine.match(/^[A-Z]+\s+(\S+)\s+HTTP\/\d(?:\.\d)?$/i);
        let pathname = "";
        if (match) {
            try { pathname = new URL(match[1], "http://tunnel.local").pathname; }
            catch (_) { pathname = match[1].split("?")[0]; }
        }

        const isControlRequest = !!match && (
            pathname === "/control" ||
            pathname === "/set" ||
            pathname === "/status" ||
            pathname === "/favicon.ico"
        );

        decided = true;
        client.removeListener("data", onData);

        if (isControlRequest) {
            client.pause();
            client.unshift(firstData);
            control.emit("connection", client);
            client.resume();
        } else {
            relayTCP(client, firstData);
        }
    }

    client.on("data", onData);
});

listener.on("error", err => console.log("[LISTENER ERROR]", err.message));
listener.listen(PORT, "0.0.0.0", () => {
    console.log("--------------------------------");
    console.log("VNPT TCP TUNNEL STARTED");
    console.log("PUBLIC PORT :", PORT);
    console.log("CONTROL URL : http://localhost:" + PORT + "/control");
    console.log("REMOTE URL  : https://localhost:" + PORT + "/");
    console.log("TARGET      : [" + TARGET_IPV6 + "]:" + TARGET_PORT);
    console.log("TCP RELAY   : net.createServer + net.connect");
    console.log("HTTP PROXY  : DISABLED");
    console.log("--------------------------------");
});
