// 3D Secure çerçevesi. Ana sayfa (aynı adres) bankanın 3D HTML'ini postMessage ile gönderir, bu
// sayfa onu çizer. srcdoc kullanılsaydı çerçeve ana sitenin sıkı güvenlik kuralını (CSP) devralır ve
// bankanın sayfası çalışmazdı; bu sayfanın kendi, banka formuna izin veren kuralı var (nginx.conf).
window.addEventListener("message", function onHtml(event) {
    if (event.origin !== window.location.origin || event.source !== window.parent) return;
    var data = event.data;
    if (!data || data.type !== "threeds-html" || typeof data.html !== "string") return;
    window.removeEventListener("message", onHtml);
    document.open();
    document.write(data.html);
    document.close();
});
window.parent.postMessage({ type: "threeds-frame-ready" }, window.location.origin);
