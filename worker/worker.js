// SaveGo Accelerator — Cloudflare Worker (MIỄN PHÍ, gói Free 100.000 lượt/ngày)
//
// Cách triển khai — chỉ làm 1 lần duy nhất, không cần thẻ:
//   1. Vào https://dash.cloudflare.com, tạo tài khoản miễn phí (chỉ cần email).
//   2. Mở "Workers & Pages" → Create → chọn "Start with Hello World" (hoặc Quick Edit).
//   3. Xoá hết code mẫu, dán toàn bộ file này vào → bấm Deploy.
//   4. Copy địa chỉ Worker, ví dụ: https://savego-accel.ten-cua-ban.workers.dev
//   5. Mở file savego-config.json ở gốc repo apk-test-app, điền địa chỉ đó vào
//      "worker_url" rồi lưu lại. App tự đọc file cấu hình này mỗi lần mở,
//      KHÔNG cần build lại app, KHÔNG cần gửi lại lên Play.
//
// Cách dùng: app gọi <worker-url>?u=<link-video-đã-mã-hoá>.
// Worker tải hộ video từ máy chủ của Cloudflare rồi trả về cho app — đoạn đường
// từ máy anh tới Cloudflare thường đỡ nghẽn hơn đường thẳng tới Douyin vào buổi tối.
// Worker chỉ chuyển tiếp (proxy) dữ liệu, không lưu trữ gì cả.
//
// Lưu ý: giữ nguyên header Range để app tải chia đoạn song song vẫn hoạt động.
export default {
  async fetch(request) {
    const url = new URL(request.url);
    const target = url.searchParams.get('u');
    if (!target) return new Response('Thieu tham so u', { status: 400 });
    let targetUrl;
    try {
      targetUrl = new URL(target);
    } catch {
      return new Response('Link khong hop le', { status: 400 });
    }
    if (targetUrl.protocol !== 'https:' && targetUrl.protocol !== 'http:') {
      return new Response('Chi ho tro http/https', { status: 400 });
    }
    const headers = new Headers();
    const range = request.headers.get('Range');
    if (range) headers.set('Range', range);
    headers.set(
      'User-Agent',
      'Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36'
    );
    headers.set('Referer', 'https://www.douyin.com/');
    const res = await fetch(targetUrl.toString(), { headers });
    const out = new Headers(res.headers);
    out.set('Access-Control-Allow-Origin', '*');
    return new Response(res.body, { status: res.status, headers: out });
  },
};
