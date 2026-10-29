Vue.component("footBar", {
  template: `
    <div class="foot">
      <a class="foot-box" :class="{active: activeBtn === 1}" href="/">
        <div class="foot-view"><i class="el-icon-s-home"></i></div>
        <div class="foot-text">首页</div>
      </a>
      <a class="foot-box" :class="{active: activeBtn === 2}" href="/map.html?v=20261029-discovery2">
        <div class="foot-view"><i class="el-icon-map-location"></i></div>
        <div class="foot-text">地图</div>
      </a>
      <a class="foot-box" href="/blog-edit.html">
        <img class="add-btn" src="/imgs/add.png" alt="">
      </a>
      <a class="foot-box" :class="{active: activeBtn === 3}" href="/messages.html?v=20261029-discovery2">
        <div class="foot-view"><i class="el-icon-chat-dot-round"></i></div>
        <div class="foot-text">消息</div>
      </a>
      <a class="foot-box" :class="{active: activeBtn === 4}" href="/info.html">
        <div class="foot-view"><i class="el-icon-user"></i></div>
        <div class="foot-text">我的</div>
      </a>
    </div>
  `,
  props: ['activeBtn']
})

