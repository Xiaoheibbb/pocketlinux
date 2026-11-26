// ---------- 指针 ----------
function sendPointer(mask,x,y){
  if(!ws||ws.readyState!==1||!fbW) return;
  const b=new Uint8Array(6);
  b[0]=5; b[1]=mask;
  b[2]=(x>>8)&255; b[3]=x&255; b[4]=(y>>8)&255; b[5]=y&255;
  send(b);
}

// ===== 触摸 =====
// 由原生 View 转发：window.plTouch(action, pts)，pts 是 0~1 相对视口坐标。
// 两种模式：
//   trackpad（默认，电脑触控板式）：单指滑动=移指针、轻点=左键、双击=双击、双指滑=滚轮、
//                                  双指轻点=右键、单指按住 0.4s 后滑=按住左键拖拽、三指上滑=键盘
//   direct（老式“点哪按哪”）：window.PL_TOUCH_MODE='direct' 时启用（保留原行为）
window.PL_VER='pl8-trackpad';
const TOUCH_MODE_DEFAULT='trackpad';
const SENS_DEFAULT=1.6, SENS_MIN=0.8, SENS_MAX=2.5;   // 灵敏度：window.PL_TRACKPAD_SENS，设置页可接
function touchMode(){
  const m=String(window.PL_TOUCH_MODE||TOUCH_MODE_DEFAULT).toLowerCase();
  return m==='direct'?'direct':'trackpad';
}
function trackpadSens(){
  let v=Number(window.PL_TRACKPAD_SENS);
  if(!isFinite(v)||v<=0) v=SENS_DEFAULT;
  return Math.min(SENS_MAX,Math.max(SENS_MIN,v));
}

// 视口 0~1 坐标 → fb 坐标。fbOf 不截断（算相对位移用）；vToFb 截断（绝对定位用）
function fbOf(fx,fy){
  const vw=document.documentElement.clientWidth||window.innerWidth;
  const vh=document.documentElement.clientHeight||window.innerHeight;
  const r=canvas.getBoundingClientRect();
  return [(fx*vw-r.left)/r.width*fbW, (fy*vh-r.top)/r.height*fbH];
}
function vToFb(fx,fy){
  const p=fbOf(fx,fy);
  return [Math.max(0,Math.min(fbW-1,Math.round(p[0]))),
          Math.max(0,Math.min(fbH-1,Math.round(p[1])))];
}

// 本地指针（触控板模式下“系统指针”的位置，fb 坐标）与 #cur 圆环
const curEl=document.getElementById('cur');
let ptrX=0, ptrY=0, ptrReady=false, dragging=false;
function moveCursor(x,y){
  if(!fbW||!fbH) return;
  const r=canvas.getBoundingClientRect();
  curEl.style.display='block';
  curEl.style.left=(r.left+x/fbW*r.width)+'px';
  curEl.style.top=(r.top+y/fbH*r.height)+'px';
}
function setCursorDrag(on){
  dragging=!!on;
  if(curEl.classList) curEl.classList.toggle('drag',!!on);
}
function clampPtr(){
  ptrX=Math.max(0,Math.min(fbW-1,Math.round(ptrX)));
  ptrY=Math.max(0,Math.min(fbH-1,Math.round(ptrY)));
}
// 首帧（serverinit）后调用：触控板模式把本地光标和 X 端指针都初始化到画面中心
function initPointer(){
  if(!fbW||!fbH) return;
  ptrReady=true;
  ptrX=Math.floor(fbW/2); ptrY=Math.floor(fbH/2);
  if(touchMode()==='trackpad'){
    moveCursor(ptrX,ptrY); setCursorDrag(false);
    sendPointer(0,ptrX,ptrY);
  }
}

// 低层点击/滚轮（坐标均为 fb 坐标）
function clickAt(x,y,btn,hold){ sendPointer(btn||1,x,y); setTimeout(()=>sendPointer(0,x,y),hold||60); }
function doubleClickAt(x,y){
  sendPointer(1,x,y);
  setTimeout(()=>sendPointer(0,x,y),35);
  setTimeout(()=>sendPointer(1,x,y),70);
  setTimeout(()=>sendPointer(0,x,y),105);
}
function wheelAt(x,y,up){ sendPointer(up?8:16,x,y); setTimeout(()=>sendPointer(0,x,y),30); }

// ===== direct：老的“点哪按哪”（window.PL_TOUCH_MODE='direct'） =====
let dMode='idle', dLastX=0, dLastY=0, dDownX=0, dDownY=0, dDownTime=0;
let dPressed=false, dMoved=false, dLongTimer=null, dLongPressed=false, dArmTimer=null, dPressedSent=false;
let dScrollPrevY=0, dScrollAcc=0, dTwoStart=0, dTwoMoved=false, dThreeStartY=0, dKbShown=false;
function directTouch(action,pts){
  const n=pts.length;
  if(action===0){                        // 第一根手指按下（延迟 80ms 再真正按下，给双指留机会）
    const [x,y]=vToFb(pts[0][0],pts[0][1]);
    dMode='single'; dDownX=dLastX=x; dDownY=dLastY=y; dDownTime=Date.now();
    dMoved=false; dLongPressed=false; dPressed=true; dPressedSent=false;
    ptrX=x; ptrY=y;
    clearTimeout(dArmTimer); clearTimeout(dLongTimer);
    dArmTimer=setTimeout(()=>{ if(dMode==='single'&&dPressed&&!dPressedSent){ sendPointer(1,dDownX,dDownY); dPressedSent=true; } },80);
    dLongTimer=setTimeout(()=>{ dLongPressed=!dMoved; },600);
    return;
  }
  if(action===5){                        // 又一根手指按下
    clearTimeout(dLongTimer); clearTimeout(dArmTimer);
    if(dPressed&&dPressedSent) sendPointer(0,dLastX,dLastY);
    dPressed=false; dPressedSent=false;
    if(n>=3){ dMode='three'; dThreeStartY=pts[0][1]; dKbShown=false; return; }
    if(n===2){ dMode='scroll'; dScrollPrevY=(pts[0][1]+(pts[1]?pts[1][1]:pts[0][1]))/2;
      dScrollAcc=0; dTwoStart=Date.now(); dTwoMoved=false; return; }
  }
  if(action===2){
    if(dMode==='three'){
      if(!dKbShown && (dThreeStartY-pts[0][1])>0.06){ dKbShown=true; plShowKeyboard(); log('键盘'); }
      return;
    }
    if(dMode==='scroll'){
      let y=pts[0][1]; if(pts.length>=2) y=(pts[0][1]+pts[1][1])/2;
      dScrollAcc+=(dScrollPrevY-y); dScrollPrevY=y;
      if(Math.abs(dScrollAcc)>0.012) dTwoMoved=true;
      const step=dScrollAcc>0.03?1:(dScrollAcc<-0.03?-1:0);
      if(step){ wheelAt(dLastX,dLastY,step>0); dScrollAcc-=step*0.03; log(step>0?'滚轮↑':'滚轮↓'); }
      return;
    }
    const [x,y]=vToFb(pts[0][0],pts[0][1]);
    if(Math.abs(x-dDownX)+Math.abs(y-dDownY)>6){ dMoved=true; clearTimeout(dLongTimer); }
    ptrX=x; ptrY=y; moveCursor(x,y);
    if(dPressed){
      if(!dPressedSent){ sendPointer(1,dDownX,dDownY); dPressedSent=true; }  // 先按在起点，抓住对象
      sendPointer(1,x,y);
    }
    dLastX=x; dLastY=y;
    return;
  }
  if(action===6) return;                 // 某根手指抬起但还有手指
  if(action===3){                        // 被系统取消：只释放，不点击
    clearTimeout(dArmTimer); clearTimeout(dLongTimer);
    if(dPressed&&dPressedSent){ moveCursor(dLastX,dLastY); sendPointer(0,dLastX,dLastY); }
    dPressed=false; dPressedSent=false; dMode='idle'; setCursorDrag(false);
    return;
  }
  if(action===1){                        // 最后一根抬起
    clearTimeout(dArmTimer); clearTimeout(dLongTimer);
    const m=dMode; dMode='idle';
    if(m==='three') return;
    if(m==='scroll'){
      if(!dTwoMoved && Date.now()-dTwoStart<350){ clickAt(ptrX,ptrY,2,60); }  // 双指轻点=中键（沿用旧行为）
      return;
    }
    const [x,y]=vToFb(pts[0][0],pts[0][1]);
    if(dPressed){
      dPressed=false;
      if(dPressedSent){
        dPressedSent=false; sendPointer(0,x,y);
        if(!dMoved && dLongPressed){ setTimeout(()=>clickAt(x,y,4,60),50); log('右键'); }
        else if(!dMoved) log('左键');
      } else if(!dMoved){
        if(dLongPressed){ clickAt(dDownX,dDownY,4,60); log('右键'); }
        else { clickAt(dDownX,dDownY,1,60); log('左键'); }
      }
    }
  }
}

// ===== trackpad：电脑触控板式（默认） =====
const HOLD_DRAG_MS=400;       // 单指按住多久后允许“按住拖拽”
const LONG_RIGHT_MS=600;      // 单指按住不动再抬手 = 右键（保留，优先级低于双指右键）
const TAP_MAX_MOVE=6;         // 判定“轻点”允许的抖动（fb px）
const TAP_DELAY_MS=200;       // 单击延迟结算，给双击留判定窗口
const TAP_DOUBLE_WINDOW=340;  // 两次轻点间隔小于此值 = 双击
const SCROLL_MARK=0.012, SCROLL_STEP=0.03;  // 双指滚动：归一化位移的判定/步长

let tMode='idle';             // idle | single | scroll | three
let tLastFx=0, tLastFy=0, tDownTime=0;
let tMoved=false, tMovedPx=0, tArmed=false, tDragging=false;
let tArmTimer=null, tLongTimer=null, tLongRight=false;
let tScrollPrevY=0, tScrollAcc=0, tTwoStart=0, tTwoMoved=false;
let tThreeStartY=0, tKbShown=false;
let tPendingClick=null, tTapUpTime=0, tSuppressTap=false;

function tpDown(pts){
  const fx=pts[0][0], fy=pts[0][1];
  const now=Date.now();
  // 上一轻点还没结算：间隔够短→双击（显式补一对快速 press/release，保证 toolkit 认双击），否则补发单击
  if(tPendingClick){
    clearTimeout(tPendingClick); tPendingClick=null;
    if(now-tTapUpTime<TAP_DOUBLE_WINDOW){
      moveCursor(ptrX,ptrY); doubleClickAt(ptrX,ptrY);
      tSuppressTap=true; log('双击');
    } else {
      clickAt(ptrX,ptrY,1,60);
    }
  }
  tMode='single';
  tDownTime=now; tLastFx=fx; tLastFy=fy; tMoved=false; tMovedPx=0;
  tArmed=false; tDragging=false; tLongRight=false; tSuppressTap=false;
  clearTimeout(tArmTimer); clearTimeout(tLongTimer);
  tArmTimer=setTimeout(()=>{ if(tMode==='single'&&!tMoved) tArmed=true; },HOLD_DRAG_MS);
  tLongTimer=setTimeout(()=>{ if(tMode==='single'&&!tMoved) tLongRight=true; },LONG_RIGHT_MS);
}
function tpPointerDown(pts){
  clearTimeout(tArmTimer); clearTimeout(tLongTimer);
  if(tDragging){ sendPointer(0,ptrX,ptrY); tDragging=false; setCursorDrag(false); }
  tArmed=false;
  const n=pts.length;
  if(n>=3){ tMode='three'; tThreeStartY=pts[0][1]; tKbShown=false; return; }
  if(n===2){ tMode='scroll'; tScrollPrevY=(pts[0][1]+pts[1][1])/2; tScrollAcc=0;
    tTwoStart=Date.now(); tTwoMoved=false; log('滚动'); return; }
}
function tpMove(pts){
  if(tMode==='three'){
    if(!tKbShown && (tThreeStartY-pts[0][1])>0.06){ tKbShown=true; plShowKeyboard(); log('键盘'); }
    return;
  }
  if(tMode==='scroll'){
    let y=pts[0][1]; if(pts.length>=2) y=(pts[0][1]+pts[1][1])/2;
    tScrollAcc+=(tScrollPrevY-y); tScrollPrevY=y;
    if(Math.abs(tScrollAcc)>SCROLL_MARK) tTwoMoved=true;
    const step=tScrollAcc>SCROLL_STEP?1:(tScrollAcc<-SCROLL_STEP?-1:0);
    if(step){ wheelAt(ptrX,ptrY,step>0); tScrollAcc-=step*SCROLL_STEP; log(step>0?'滚轮↑':'滚轮↓'); }
    return;
  }
  if(tMode!=='single') return;
  const fx=pts[0][0], fy=pts[0][1];
  const [cx,cy]=fbOf(fx,fy), [lx,ly]=fbOf(tLastFx,tLastFy);
  const ddx=cx-lx, ddy=cy-ly;
  tLastFx=fx; tLastFy=fy;
  if(!tMoved){
    tMovedPx+=Math.abs(ddx)+Math.abs(ddy);
    if(tMovedPx>TAP_MAX_MOVE){ tMoved=true; clearTimeout(tLongTimer); }
  }
  const k=trackpadSens();
  if(tArmed){                    // 按住 0.4s 后再滑 = 按住左键拖拽
    if(!tDragging){
      tDragging=true; setCursorDrag(true); moveCursor(ptrX,ptrY);
      sendPointer(1,ptrX,ptrY);  // 先在指针当前位置按下（抓住窗口/图标），再按相对位移拖
      log('拖拽');
    }
    ptrX+=ddx*k; ptrY+=ddy*k; clampPtr();
    moveCursor(ptrX,ptrY); sendPointer(1,ptrX,ptrY);
    return;
  }
  ptrX+=ddx*k; ptrY+=ddy*k; clampPtr();
  moveCursor(ptrX,ptrY); sendPointer(0,ptrX,ptrY);   // 不按键：只移指针
}
function tpUp(pts){
  clearTimeout(tArmTimer); clearTimeout(tLongTimer);
  const m=tMode; tMode='idle';
  if(m==='three') return;
  if(m==='scroll'){              // 双指轻点 = 右键
    if(!tTwoMoved && Date.now()-tTwoStart<350){ moveCursor(ptrX,ptrY); clickAt(ptrX,ptrY,4,60); log('右键'); }
    return;
  }
  if(tSuppressTap){ tSuppressTap=false; tArmed=false; tDragging=false; setCursorDrag(false); return; }
  if(tDragging){                 // 拖拽松手：释放左键
    tDragging=false; tArmed=false; setCursorDrag(false); sendPointer(0,ptrX,ptrY); log('放开');
    return;
  }
  tArmed=false;
  if(tMoved) return;             // 滑过：只移指针，不点击
  if(tLongRight){ clickAt(ptrX,ptrY,4,60); log('右键'); return; }  // 长按不动再抬手 = 右键
  tTapUpTime=Date.now();         // 轻点：延迟结算，等可能的第二次轻点
  if(tPendingClick) clearTimeout(tPendingClick);
  tPendingClick=setTimeout(()=>{ tPendingClick=null; clickAt(ptrX,ptrY,1,60); log('左键'); },TAP_DELAY_MS);
}
function tpCancel(){
  clearTimeout(tArmTimer); clearTimeout(tLongTimer);
  if(tDragging){ sendPointer(0,ptrX,ptrY); tDragging=false; setCursorDrag(false); }
  tArmed=false; tMode='idle';
}

window.plTouch = function(action, pts){
  if(!fbW||!pts||!pts.length) return;
  if(touchMode()==='direct') return directTouch(action,pts);
  if(action===0) return tpDown(pts);
  if(action===5) return tpPointerDown(pts);
  if(action===2) return tpMove(pts);
  if(action===6) return;
  if(action===3) return tpCancel();
  if(action===1) return tpUp(pts);
};

// 供设置页/原生实时调用（也接受 window.PL_TOUCH_MODE / window.PL_TRACKPAD_SENS 直接赋值）
window.plSetTouchMode=function(m){
  const v=(String(m||'').toLowerCase()==='direct')?'direct':'trackpad';
  window.PL_TOUCH_MODE=v;
  if(tPendingClick){ clearTimeout(tPendingClick); tPendingClick=null; }
  curEl.style.display='none'; setCursorDrag(false);
  if(v==='trackpad'){
    if(!ptrReady) initPointer();
    else { moveCursor(ptrX,ptrY); sendPointer(0,ptrX,ptrY); }
    log('触控板模式');
  } else {
    log('直接触摸模式');
  }
};
window.plSetTrackpadSens=function(v){
  const f=Number(v);
  if(isFinite(f)&&f>0) window.PL_TRACKPAD_SENS=f;
  log('灵敏度 '+trackpadSens().toFixed(1)+'×');
};
window.plTouchInfo=function(){
  return {mode:touchMode(),sens:trackpadSens(),x:ptrX,y:ptrY,ready:ptrReady};
};
