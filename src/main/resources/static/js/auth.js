/**
 * 共享登录守卫与登出：替代各页面重复的 fetch('/api/auth/me') 内联守卫。
 * 用法：
 *   careAuth.ensure(name => { ... });   // 未登录自动跳 /login
 *   const name = await careAuth.ensure();
 *   careAuth.logout();
 */
window.careAuth = {
    ensure(onUser) {
        return fetch('/api/auth/me')
            .then(response => response.json())
            .then(result => {
                if (!result || result.code !== 200) {
                    location.href = '/login';
                    throw new Error('未登录');
                }
                if (typeof onUser === 'function') {
                    onUser(result.data.userName);
                }
                return result.data.userName;
            })
            .catch(error => {
                location.href = '/login';
                throw error;
            });
    },
    logout() {
        fetch('/api/auth/logout', { method: 'POST' }).then(() => location.href = '/login');
    }
};
