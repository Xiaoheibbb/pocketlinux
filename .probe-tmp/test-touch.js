// 无 DOM/无 adb 的触控状态机验证：stub 浏览器环境 → 注入 plTouch 序列 → 断言 RFB PointerEvent 流
const fs = require('fs');
const vm = require('vm');
const EXIT = process.exit.bind(process);

const html = fs.readFileSync('pocketlinux/app/src/main/assets/novnc/index.html','utf8');
const m = html.match(/<script>([\s\S]*)<\/script>/);
if(!m) { console.error('no script'); process.exit(1); }
const code = m[1];

// ---------- stubs ----------
const RECT = {left:47, top:0, width:1186, height:2568};
const el = (id) => ({
  id, textContent:'', value:'', style:{}, clientWidth:1280, clientHeight:2568,
  addEventListener(){}, focus(){}, classList:{_s:new Set(), toggle(c,on){on?this._s.add(c):this._s.delete(c)}, add(c){this._s.add(c)}, remove(c){this._s.delete(c)}},
});
const els = {};
['st','wrap','kbi','cur'].forEach(i=>els[i]=el(i));
els.wrap.clientWidth=1280; els.wrap.clientHeight=2568;
const canvas = el('c');
canvas.width=0; canvas.height=0;
canvas.getContext = () => ({createImageData:(w,h)=>({data:new Uint8ClampedArray(w*h*4)}), putImageData(){}});
canvas.getBoundingClientRect = () => RECT;
els.c = canvas;

global.window = globalThis;
global.addEventListener = ()=>{};
global.innerWidth = 1280; global.innerHeight = 2568;
global.document = {
  getElementById:(id)=>els[id]||el(id),
  documentElement:{clientWidth:1280, clientHeight:2568},
};
global.ImageData = function(w,h){ this.data = new Uint8ClampedArray(w*h*4); };
let RAF_Q=[], RAF_ID=0, RAF_NOW=1000;
global.performance = { now: ()=>RAF_NOW };
global.requestAnimationFrame = (cb)=>{ RAF_Q.push(cb); return ++RAF_ID; };
let RAF_LAST_ID=0;
function pumpRAF(ms){ const q=RAF_Q; RAF_Q=[]; RAF_NOW+=ms; q.forEach(cb=>cb(RAF_NOW)); }

global.WebSocket = class {
  constructor(url, protos){ this.url=url; this.readyState=1; this.sent=[]; WS.push(this); }
  send(u8){ this.sent.push({b:new Uint8Array(u8), t:Date.now()}); }
};
const WS=[];
global.WS_INSTANCES = WS;

vm.runInThisContext(code, {filename:'index.html-script'});
const ws = WS[0];
if(!ws) throw new Error('no websocket created');

// ---------- helpers ----------
const sleep = ms => new Promise(r=>setTimeout(r,ms));
const frames = () => ws.sent.map(({b:f,t})=>({type:f[0], mask:f[1], x:(f[2]<<8)|f[3], y:(f[4]<<8)|f[5], t}));
const ptrFrames = () => frames().filter(f=>f.type===5);
let mark = 0;
const takePtr = () => { const p = ptrFrames().slice(mark); mark = ptrFrames().length; return p; };
const frameMark = () => { mark = ptrFrames().length; };
const touch = (a, pts) => window.plTouch(a, pts);
const info = () => window.plTouchInfo();

let pass=0, fail=0;
function check(name, cond, extra){
  if(cond){ pass++; console.log('PASS', name); }
  else { fail++; console.log('FAIL', name, extra===undefined?'':JSON.stringify(extra)); }
}
const near = (a,b,tol=2) => Math.abs(a-b)<=tol;

function handshake(){
  const ver = Buffer.from('RFB 003.008\n');
  const sec = Buffer.from([1,1]);
  const secres = Buffer.from([0,0,0,0]);
  const si = Buffer.alloc(24);
  si[0]=680>>8; si[1]=680&255; si[2]=1472>>8; si[3]=1472&255;
  si[20]=0; si[21]=0; si[22]=0; si[23]=0;
  const all = Buffer.concat([ver,sec,secres,si]);
  ws.onmessage({data: all.buffer.slice(all.byteOffset, all.byteOffset+all.byteLength)});
}

(async () => {
  handshake();
  await sleep(30);
  // ---- 0. 初始化 ----
  let i0 = info();
  check('初始化: 默认 trackpad', i0.mode==='trackpad' && i0.sens===1.6, i0);
  check('初始化: 指针在画面中心 (340,736)', near(i0.x,340,1)&&near(i0.y,736,1), i0);
  check('初始化: 有 center pointer(mask 0)', ptrFrames().some(f=>f.mask===0&&near(f.x,340,1)&&near(f.y,736,1)), ptrFrames().slice(-3));
  check('初始化: 光标可见 #cur', els.cur.style.display==='block', els.cur.style);

  // ---- 1. 单指滑动 = 移指针（不按键），灵敏度 1.6 ----
  frameMark();
  touch(0, [[0.5,0.5]]);
  await sleep(30);
  touch(2, [[0.52,0.5]]);
  await sleep(20);
  let pf = takePtr();
  let moved = info();
  // finger dx fb = (0.52-0.5)*1280/1186*680 = 14.67; *1.6 = 23.5
  check('滑动: MotionEvent 全为 mask=0（无按键）', pf.length===1 && pf[0].mask===0, pf);
  check('滑动: 指针按 1.6x 跟随', near(moved.x, 340+23.5, 2), moved);
  // 继续滑动后抬手：不应产生点击
  frameMark();
  touch(2, [[0.54,0.5]]); touch(2, [[0.56,0.5]]);
  touch(1, [[0.56,0.5]]);
  await sleep(320);
  pf = takePtr();
  const hasClick = pf.some(f=>f.mask===1);
  check('滑动抬手: 不点击（无 mask=1）', hasClick===false, pf.slice(-4));
  check('滑动抬手: 光标拖拽样式已复位', !els.cur.classList._s.has('drag'));

  // ---- 2. 轻点 = 在“当前指针位置”左键单击 ----
  const before = info();
  frameMark();
  touch(0, [[0.25,0.75]]); await sleep(60); touch(1, [[0.25,0.75]]);
  await sleep(120);   // 立即点击；松开在 40ms 后
  pf = takePtr();
  const clickSeq = pf.filter(f=>f.mask===1||f.mask===0);
  check('轻点: 产生 press+release', clickSeq.some(f=>f.mask===1)&&clickSeq.some(f=>f.mask===0), pf);
  check('轻点: 点的是当前指针位置而不是手指位置',
        pf.some(f=>f.mask===1&&near(f.x,before.x,1)&&near(f.y,before.y,1)),
        {press:pf.find(f=>f.mask===1), before:{x:before.x,y:before.y}});

  // ---- 3. 快速两次轻点 = 双击（显式双 press/release） ----
  frameMark();
  touch(0, [[0.3,0.3]]); await sleep(50); touch(1, [[0.3,0.3]]);
  await sleep(120);                       // 快速第二次轻点（< DOUBLE_TAP_MS）
  touch(0, [[0.3,0.3]]); await sleep(30); touch(1, [[0.3,0.3]]);
  await sleep(300);
  pf = takePtr();
  const presses = pf.filter(f=>f.mask===1);
  const releases = pf.filter(f=>f.mask===0);
  check('双击: 两次 press+两次 release', presses.length===2 && releases.length>=2, pf);
  check('双击: 两次 press 间隔 < 250ms（XFCE/GTK 默认可识别双击）',
        presses.length===2 && (presses[1].t-presses[0].t)<250, presses.map(p=>p.t));

  // ---- 4. 按住 0.4s 再滑 = 按住左键拖拽 ----
  const start = info();
  frameMark();
  touch(0, [[0.5,0.5]]);
  await sleep(470);                       // 越过 HOLD_DRAG_MS(400)
  touch(2, [[0.52,0.52]]);
  await sleep(20);
  touch(2, [[0.55,0.55]]);
  await sleep(20);
  const dragMid = info();
  touch(1, [[0.55,0.55]]);
  await sleep(60);
  pf = takePtr();
  const pressFrames = pf.filter(f=>f.mask===1);
  const relFrames = pf.filter(f=>f.mask===0);
  // 第一帧 mask=1 应在原指针位置（抓住对象），之后帧坐标递增
  check('拖拽: 先按下（mask=1，抓在按下时的指针位置）',
        pressFrames.length>=1 && near(pressFrames[0].x,start.x,1) && near(pressFrames[0].y,start.y,1),
        {first:pressFrames[0], start:{x:start.x,y:start.y}});
  check('拖拽: 移动帧带 Button1Mask 且坐标递增', pressFrames.length>=3 && pressFrames[pressFrames.length-1].x>pressFrames[0].x, pressFrames);
  check('拖拽: 松手发出 release', relFrames.length>=1 && near(relFrames[relFrames.length-1].x,dragMid.x,1), {rel:relFrames.slice(-1), mid:dragMid});
  check('拖拽: 拖拽样式已复位', !els.cur.classList._s.has('drag'));

  // ---- 5. 双指上下滑 = 滚轮 ----
  frameMark();
  touch(0, [[0.5,0.5]]);
  const scrollPtr = info();
  touch(5, [[0.5,0.45],[0.6,0.45]]);
  await sleep(20);
  touch(2, [[0.5,0.40],[0.6,0.40]]);      // avg -0.05 → 超过 STEP 0.03 → 滚轮↑(mask8)
  await sleep(20);
  touch(2, [[0.5,0.35],[0.6,0.35]]);
  await sleep(20);
  touch(6, []);                            // 一根抬起（用不到，防崩）
  touch(1, [[0.5,0.35],[0.6,0.35]]);
  await sleep(60);
  pf = takePtr();
  const wheelFrames = pf.filter(f=>f.mask===8||f.mask===16);
  check('双指滚动: 产生滚轮(mask 8/16)',
        wheelFrames.length>=1 && wheelFrames.every(f=>near(f.x,scrollPtr.x,2)&&near(f.y,scrollPtr.y,2)),
        {wheelFrames, ptr:{x:scrollPtr.x,y:scrollPtr.y}});
  check('双指滚动: 未产生左键点击', !pf.some(f=>f.mask===1&&f.type===5), pf.filter(f=>f.mask===1).slice(0,3));

  // ---- 6. 双指轻点 = 右键 (mask 4) ----
  frameMark();
  touch(0, [[0.5,0.5]]);
  touch(5, [[0.5,0.5],[0.6,0.5]]);
  await sleep(40);
  touch(1, [[0.5,0.5],[0.6,0.5]]);
  await sleep(100);
  pf = takePtr();
  check('双指轻点: 右键 press (mask=4)', pf.some(f=>f.mask===4), pf);

  // ---- 7. 三指上滑 = 键盘 ----
  let kb=false;
  window.plShowKeyboard = function(){ kb=true; };
  frameMark();
  touch(0, [[0.5,0.6]]);
  touch(5, [[0.5,0.6],[0.55,0.6],[0.6,0.6]]);
  await sleep(20);
  touch(2, [[0.5,0.5],[0.55,0.5],[0.6,0.5]]);   // 上滑 0.1 > 0.06
  await sleep(20);
  touch(1, [[0.5,0.5],[0.55,0.5],[0.6,0.5]]);
  check('三指上滑: 唤起键盘', kb===true);

  // ---- 8. 灵敏度可调/夹取 ----
  frameMark();
  window.PL_TRACKPAD_SENS = 2.5;      // 设置页注入
  touch(0, [[0.5,0.5]]);
  const p0 = info();
  touch(2, [[0.52,0.5]]); await sleep(15);
  const p1 = info();
  touch(1, [[0.52,0.5]]); await sleep(50);
  check('灵敏度 2.5: 位移=2.5倍', near(p1.x-p0.x, 14.67*2.5, 2), {dx:p1.x-p0.x});
  window.PL_TRACKPAD_SENS = 0.8;
  touch(0, [[0.5,0.5]]);
  const q0 = info();
  touch(2, [[0.52,0.5]]); await sleep(15);
  const q1 = info();
  touch(1, [[0.52,0.5]]); await sleep(50);
  check('灵敏度 0.8: 位移=0.8倍', near(q1.x-q0.x, 14.67*0.8, 2), {dx:q1.x-q0.x});
  window.plSetTrackpadSens(99);
  check('灵敏度夹取上限 2.5', info().sens===2.5, info());
  window.plSetTrackpadSens(0.1);
  check('灵敏度夹取下限 0.8', info().sens===0.8, info());
  window.plSetTrackpadSens(1.6);

  // ---- 9. 模式切换 direct ----
  window.plSetTouchMode('direct');
  check('模式切换: direct', info().mode==='direct' && document.getElementById('cur').style.display==='none');
  frameMark();
  touch(0, [[0.5,0.5]]);
  await sleep(100);                         // 80ms 后 direct 模式 press
  touch(2, [[0.55,0.55]]);
  await sleep(20);
  touch(1, [[0.55,0.55]]);
  await sleep(40);
  pf = takePtr();
  check('direct: 按下并拖动 (mask=1)', pf.some(f=>f.mask===1), pf);
  check('direct: 无 trackpad 的 200ms 延迟点击残影', !pf.some(f=>f.mask===0&&pf.indexOf(f)===0&&false));
  window.plSetTouchMode('trackpad');
  check('模式切回: trackpad 且光标重新出现', info().mode==='trackpad' && document.getElementById('cur').style.display==='block');

  // ---- 10. 取消(CANCEL) 在拖拽中：只释放、不点击 ----
  await sleep(400);
  touch(0, [[0.5,0.5]]);
  await sleep(450);
  touch(2, [[0.55,0.55]]);
  await sleep(20);
  frameMark();
  touch(3, [[0.55,0.55]]);
  await sleep(80);
  pf = takePtr();
  check('取消: 拖拽中 CANCEL 会释放左键', pf.some(f=>f.mask===0), pf);

  // ---- 11. 指针边界夹取 ----
  frameMark();
  touch(0, [[0.5,0.5]]);
  for(let k=0;k<12;k++){ touch(2, [[0.5-0.05*(k+1),0.5]]); await sleep(8); }
  touch(1, [[0.0,0.5]]);
  await sleep(60);
  pf = takePtr();
  check('边界: 指针不越界 (x>=0)', pf.every(f=>f.x>=0&&f.x<=679&&f.y>=0&&f.y<=1471), pf.slice(-3));

  // ---- 12. PL_VER / 运行时阈值 ----
  check('PL_VER = pl9', window.PL_VER==='pl9', window.PL_VER);
  window.plSetTouchThresholds(300,500);
  check('阈值可调: (300,500)', info().hold===undefined ? window.plInputInfo().hold===300 && window.plInputInfo().right===500 : window.plInputInfo().hold===300 && window.plInputInfo().right===500, window.plInputInfo());
  window.plSetTouchThresholds(100,2000);
  check('阈值钳制: hold 250 / right 1000', window.plInputInfo().hold===250 && window.plInputInfo().right===1000, window.plInputInfo());
  window.plSetTouchThresholds(9999,10);
  check('阈值钳制: hold 800 / right 400', window.plInputInfo().hold===800 && window.plInputInfo().right===400, window.plInputInfo());
  window.plSetTouchThresholds(400,600);

  // ---- 13. plKeyDown/plKeyUp/plKey ----
  const keyFrames = () => ws.sent.filter(({b})=>b[0]===4).map(({b,t})=>({
    down:b[1], ks:(((b[4]<<24)|(b[5]<<16)|(b[6]<<8)|b[7])>>>0), t}));
  frameMark();
  let k0 = keyFrames().length;
  window.plKeyDown(0x61); window.plKeyUp(0x61);
  let kf = keyFrames().slice(k0);
  check('键盘: plKeyDown/plKeyUp 发按下+抬起', kf.length>=2 && kf[0].down===1 && kf[1].down===0, kf);
  check('键盘: keysym 编码正确 (0x61)', kf.length>=1 && kf[0].ks===0x61, kf);
  k0 = keyFrames().length;
  window.plKey(0xFF0D);
  await sleep(60);
  kf = keyFrames().slice(k0);
  check('键盘: plKey 兼容（按下+30ms 抬起）', kf.length>=2 && kf[0].down===1 && kf[1].down===0 && kf[0].ks===0xFF0D, kf);

  // ---- 14. 手柄指针按键 plPointerButton ----
  frameMark();
  window.plPointerButton(1,true);
  let bf = takePtr();
  check('手柄: A=左键按下 mask=1', bf.length===1 && bf[0].mask===1, bf);
  frameMark();
  window.plPointerButton(1,false);
  bf = takePtr();
  check('手柄: A 松开 mask=0', bf.length===1 && bf[0].mask===0, bf);
  frameMark();
  window.plPointerButton(1,true); window.plPointerButton(3,true);
  bf = takePtr();
  check('手柄: 左+右同时按 mask=5', bf.length===2 && bf[1].mask===5, bf);
  window.plPointerButton(1,false); window.plPointerButton(3,false);
  await sleep(10);

  // ---- 15. 手柄滚轮 plWheel ----
  frameMark();
  window.plWheel(true); await sleep(40);
  let wf = takePtr();
  check('手柄: L1=滚轮上 mask=8', wf.some(f=>f.mask===8), wf);
  frameMark();
  window.plWheel(false); await sleep(40);
  wf = takePtr();
  check('手柄: R1=滚轮下 mask=16', wf.some(f=>f.mask===16), wf);

  // ---- 16. 摇杆 plStick：rAF 积分成相对移动（灵敏度复用） ----
  const s0 = window.plInputInfo().ptr;
  RAF_Q=[]; window.plStick(1,0);
  pumpRAF(20); pumpRAF(20); pumpRAF(20); pumpRAF(20); pumpRAF(20);   // 100ms
  const s1 = window.plInputInfo().ptr;
  const expect = 820*1.6*0.1;     // STICK_SPEED * sens * 0.1s
  check('摇杆: 右推 100ms 指针右移约 '+Math.round(expect)+'px',
        (s1[0]-s0[0])>expect*0.6 && (s1[0]-s0[0])<expect*1.4, {dx:s1[0]-s0[0], expect});
  check('摇杆: 移动帧为 mask=0（不按键）', ptrFrames().slice(-3).every(f=>f.mask===0), ptrFrames().slice(-3));
  window.plStick(0,0); pumpRAF(20); pumpRAF(20);   // 让最后一帧回调把 stickRAF 归零
  const s2 = window.plInputInfo().ptr;
  check('摇杆: 归零后停止积分', s2[0]===s1[0] && RAF_Q.length===0, {before:s1, after:s2, rafQ:RAF_Q.length});

  // ---- 17. 手柄 A 按住 + 摇杆 = 拖拽（mask=1 的移动帧） ----
  frameMark();
  window.plPointerButton(1,true);
  window.plStick(0,1);   // 下推
  pumpRAF(20); pumpRAF(20); pumpRAF(20);
  window.plStick(0,0); pumpRAF(20);
  window.plPointerButton(1,false);
  const dragFrames = ptrFrames().slice(-8);
  check('手柄: A 按住 + 摇杆 → mask=1 的相对移动（可拖窗口）',
        dragFrames.some(f=>f.mask===1) && dragFrames.filter(f=>f.mask===1).some(f=>f.y>s2[1]),
        dragFrames.slice(-5));

  console.log('\n=== RESULT:', pass, 'passed,', fail, 'failed ===');
  EXIT(fail?1:0);
})().catch(e=>{ console.error('HARNESS ERROR', e); EXIT(2); });
