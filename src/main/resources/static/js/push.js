/**
 * Web Push 订阅共享脚本：
 * - 页面存在 #pushToggle 时：绑定开关（状态回显 + 订阅/取消），不自动订阅
 * - 无开关的页面：加载后自动订阅
 */
const VAPID_PUBLIC_KEY = 'BMaNpciuS15Tqj9lE27TvS67e0Dwa0yiQD3SKya1OwnoAPnrVlust4JLM3NvhdQPVz-np174cixSInkmGbbFj_M';
const SW_PATH = '/js/service-worker.js';

function urlB64ToUint8Array(base64String) {
    const padding = '='.repeat((4 - base64String.length % 4) % 4);
    const base64 = (base64String + padding).replace(/-/g, '+').replace(/_/g, '/');
    const rawData = atob(base64);
    return new Uint8Array([...rawData].map(c => c.charCodeAt(0)));
}

async function sendSubscriptionToServer(sub) {
    const p256dh = btoa(String.fromCharCode.apply(null, new Uint8Array(sub.getKey('p256dh'))));
    const auth = btoa(String.fromCharCode.apply(null, new Uint8Array(sub.getKey('auth'))));
    const res = await fetch('/api/push/subscribe', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            endpoint: sub.endpoint,
            p256dh: p256dh,
            auth: auth,
            userAgent: navigator.userAgent
        })
    });
    const data = await res.json();
    if (data.code !== 200) throw new Error(data.message || 'subscribe failed');
}

window.checkPushStatus = async function () {
    const toggle = document.getElementById('pushToggle');
    if (!toggle) return;
    if (!('serviceWorker' in navigator) || !('PushManager' in window)) {
        toggle.disabled = true;
        return;
    }
    try {
        const reg = await navigator.serviceWorker.register(SW_PATH);
        const sub = await reg.pushManager.getSubscription();
        toggle.checked = !!sub;
    } catch (e) {}
};

window.togglePush = async function () {
    const toggle = document.getElementById('pushToggle');
    try {
        const reg = await navigator.serviceWorker.getRegistration(SW_PATH) ||
            await navigator.serviceWorker.register(SW_PATH);

        if (toggle.checked) {
            const sub = await reg.pushManager.subscribe({
                userVisibleOnly: true,
                applicationServerKey: urlB64ToUint8Array(VAPID_PUBLIC_KEY)
            });
            await sendSubscriptionToServer(sub);
        } else {
            const sub = await reg.pushManager.getSubscription();
            if (sub) {
                await sub.unsubscribe();
                await fetch('/api/push/unsubscribe', {
                    method: 'DELETE',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ endpoint: sub.endpoint })
                });
            }
        }
    } catch (e) {
        toggle.checked = !toggle.checked;
    }
};

async function subscribePush() {
    if (!('serviceWorker' in navigator) || !('PushManager' in window)) {
        console.log('Push not supported');
        return;
    }
    try {
        const reg = await navigator.serviceWorker.register(SW_PATH);
        const sub = await reg.pushManager.subscribe({
            userVisibleOnly: true,
            applicationServerKey: urlB64ToUint8Array(VAPID_PUBLIC_KEY)
        });
        await sendSubscriptionToServer(sub);
        console.log('Push subscribed');
    } catch (e) {
        console.error('Push subscription failed:', e);
    }
}

function bootstrapPush() {
    if (document.getElementById('pushToggle')) {
        window.checkPushStatus();
        return;
    }
    subscribePush();
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', bootstrapPush);
} else {
    bootstrapPush();
}
