(function () {
  const key = 'hmdp.discovery.location';
  const sample = {x:120.149993,y:30.334229,source:'sample',label:'杭州示例位置'};
  function valid(p) {
    return p && Number.isFinite(p.x) && Number.isFinite(p.y) && Math.abs(p.x)<=180 && Math.abs(p.y)<=90;
  }
  window.discoveryLocation = {
    get() {
      try {
        const p = JSON.parse(localStorage.getItem(key));
        if (valid(p) && p.source === 'device') return {x:p.x,y:p.y,source:'device',label:'我的位置'};
      } catch (e) {}
      return Object.assign({}, sample);
    },
    sample() { try { localStorage.removeItem(key); } catch(e) {} return Object.assign({}, sample); },
    locate() {
      return new Promise((resolve,reject) => {
        if (!navigator.geolocation) return reject(new Error('当前浏览器不支持定位，请使用示例位置浏览'));
        navigator.geolocation.getCurrentPosition(position => {
          const p = {x:position.coords.longitude,y:position.coords.latitude,source:'device',label:'我的位置'};
          if (!valid(p)) return reject(new Error('定位结果无效，请重试'));
          try { localStorage.setItem(key,JSON.stringify(p)); } catch(e) {}
          resolve(p);
        }, error => reject(new Error(error.code === 1 ? '未获得定位权限，可继续使用示例位置浏览' : '暂时无法定位，请重试或使用示例位置')),
        {enableHighAccuracy:false,timeout:10000,maximumAge:300000});
      });
    },
    distance(meters) {
      const n = Number(meters);
      return Number.isFinite(n) && n>=0 ? (n<1000 ? Math.round(n)+'米' : (n/1000).toFixed(1)+'公里') : '';
    }
  };
})();
