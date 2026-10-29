const commonURL = '/api';
axios.defaults.baseURL = commonURL;
axios.defaults.timeout = 15000;
axios.interceptors.request.use(config => {
  const currentToken = sessionStorage.getItem('token');
  if (currentToken) config.headers.authorization = currentToken;
  return config;
});
axios.interceptors.response.use(response => {
  if (!response.data || response.data.success !== true) {
    return Promise.reject(response.data && response.data.errorMsg || '请求未成功，请重试');
  }
  return response.data;
}, error => {
  if (error.response && error.response.status === 401) {
    sessionStorage.removeItem('token');
    if (!(error.config && error.config.skipAuthRedirect)) util.toLogin();
    return Promise.reject('请先登录');
  }
  if (error.code === 'ECONNABORTED') return Promise.reject('请求超时，请稍后重试');
  if (!error.response) return Promise.reject('网络连接失败，请检查网络后重试');
  if (error.response.status === 413) return Promise.reject('图片过大，请选择5MB以内的图片');
  return Promise.reject('服务暂时不可用，请稍后重试');
});
axios.defaults.paramsSerializer = params => Object.keys(params)
  .filter(key => params[key] !== undefined && params[key] !== null && params[key] !== '')
  .map(key => encodeURIComponent(key) + '=' + encodeURIComponent(String(params[key])))
  .join('&');
const util = {
  commonURL,
  getUrlParam(name) { return new URLSearchParams(location.search).get(name) || ''; },
  toLogin() {
    const next = location.pathname + location.search;
    location.href = '/login.html?redirect=' + encodeURIComponent(next);
  },
  loginDestination() {
    const next = this.getUrlParam('redirect');
    return next.startsWith('/') && !next.startsWith('//') && !next.includes('\\') && !/^\/login2?\.html/.test(next) ? next : '/info.html';
  },
  imageUrl(value) {
    if (!value || typeof value !== 'string') return '';
    const path = value.trim().replace(/\\/g, '/');
    if (/^https?:\/\//i.test(path)) return path;
    if (/^data:image\/(png|jpeg|gif|webp);base64,/i.test(path)) return path;
    if (/^\/\//.test(path) || /^[a-z]+:/i.test(path) || path.includes('..')) return '';
    if (/^\/?imgs\//.test(path)) return '/' + path.replace(/^\//, '');
    if (/^\/?(blogs|icons|shop|types)\//.test(path)) return '/imgs/' + path.replace(/^\//, '');
    return path.startsWith('/') ? path : '/imgs/' + path;
  },
  imageList(value) {
    const list = Array.isArray(value) ? value : String(value || '').split(',');
    return list.map(item => this.imageUrl(item)).filter(Boolean);
  },
  firstImage(value) { return this.imageList(value)[0] || '/imgs/image-placeholder.svg'; },
  safeContent(value) {
    const doc = new DOMParser().parseFromString(String(value || ''), 'text/html');
    const allowed = new Set(['P','BR','DIV','SPAN','STRONG','B','EM','I','U','UL','OL','LI','BLOCKQUOTE','H2','H3','H4','IMG']);
    const clean = parent => Array.from(parent.children).forEach(node => {
      if (['SCRIPT','STYLE','IFRAME','OBJECT','EMBED','SVG','MATH','LINK','META'].includes(node.tagName)) { node.remove(); return; }
      clean(node);
      if (!allowed.has(node.tagName)) { node.replaceWith(...node.childNodes); return; }
      const source = node.tagName === 'IMG' ? this.imageUrl(node.getAttribute('src')) : '';
      Array.from(node.attributes).forEach(attr => node.removeAttribute(attr.name));
      if (node.tagName === 'IMG') {
        if (!source) { node.remove(); return; }
        node.setAttribute('src', source); node.setAttribute('alt', '笔记图片'); node.setAttribute('loading', 'lazy');
      }
    });
    clean(doc.body);
    return doc.body.innerHTML;
  },
  formatPrice(val) {
    if (typeof val === 'string') {
      if (isNaN(val)) {
        return null;
      }
      // 价格转为整数
      const index = val.lastIndexOf(".");
      let p = "";
      if (index < 0) {
        // 无小数
        p = val + "00";
      } else if (index === p.length - 2) {
        // 1位小数
        p = val.replace("\.", "") + "0";
      } else {
        // 2位小数
        p = val.replace("\.", "")
      }
      return parseInt(p);
    } else if (typeof val === 'number') {
      if (!val) {
        return null;
      }
      const s = val + '';
      if (s.length === 0) {
        return "0.00";
      }
      if (s.length === 1) {
        return "0.0" + val;
      }
      if (s.length === 2) {
        return "0." + val;
      }
      const i = s.indexOf(".");
      if (i < 0) {
        return s.substring(0, s.length - 2) + "." + s.substring(s.length - 2)
      }
      const num = s.substring(0, i) + s.substring(i + 1);
      if (i === 1) {
        // 1位整数
        return "0.0" + num;
      }
      if (i === 2) {
        return "0." + num;
      }
      if (i > 2) {
        return num.substring(0, i - 2) + "." + num.substring(i - 2)
      }
    }
  }
}


Vue.mixin({ methods: {
  imageUrl: value => util.imageUrl(value),
  firstImage: value => util.firstImage(value),
  imageError(event) {
    const img = event.target;
    if (img.dataset.fallbackApplied) return;
    img.dataset.fallbackApplied = '1'; img.src = '/imgs/image-placeholder.svg';
  },
  toBlogDetail(blog) { if (blog && blog.id) location.href = '/blog-detail.html?id=' + encodeURIComponent(blog.id); },
  toPage(index) { location.href = ({1:'/',2:'/map.html',3:'/messages.html',4:'/info.html'})[index] || '/'; }
}});
