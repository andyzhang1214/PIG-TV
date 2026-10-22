/**
 * 把布局/drawable 里的设计稿像素值换算成物理毫米。
 *
 * 只跑一次。跑完会写 _backup/.scaled 锁，防止重复乘。
 *
 * 基准 55 寸 16:9：1920px ↔ 1217.6mm => 1px = 0.634mm
 * 换电视尺寸改 RATIO 后，先删 _backup/.scaled 再跑（需先把文件恢复成设计稿值）。
 */
const fs = require('fs');
const path = require('path');

const RATIO = 0.634;
const ROOT = '/Users/andy/WorkBuddy/wbWorkSpaces/tvbox/Box/app/src/main';
const LOCK = '/Users/andy/WorkBuddy/wbWorkSpaces/tvbox/Box/_backup/.scaled';

if (fs.existsSync(LOCK)) {
  console.log('已经换算过（存在 _backup/.scaled），跳过。');
  process.exit(0);
}

const FILES = [
  'res/layout/activity_home.xml',
  'res/layout/item_home_app.xml',
  'res/drawable/h_app_bg.xml',
  'res/drawable/h_app_ai_bg.xml',
  'res/drawable/h_pill_bg.xml',
  'res/drawable/h_ok_key.xml',
  'res/drawable/h_poster_ai_bg.xml',
];

const round1 = (v) => String(Math.round(v * 10) / 10);

let total = 0;
for (const rel of FILES) {
  const p = path.join(ROOT, rel);
  if (!fs.existsSync(p)) { console.log('skip:', rel); continue; }
  const before = fs.readFileSync(p, 'utf8');
  let n = 0;
  const after = before.replace(/(-?\d+(?:\.\d+)?)mm\b/g, (m, num) => {
    n++;
    return round1(parseFloat(num) * RATIO) + 'mm';
  });
  if (n > 0) {
    fs.writeFileSync(p, after);
    total += n;
    console.log(`${rel}: ${n} 处`);
  }
}

// Java 里手写的 mm 字号 / 间距
const JAVA = path.join(ROOT, 'java/com/github/tvbox/osc/ui/activity/HomeActivity.java');
let src = fs.readFileSync(JAVA, 'utf8');
const jBefore = src;
src = src.replace(/COMPLEX_UNIT_MM,\s*(\d+(?:\.\d+)?)/g, (m, num) =>
  'COMPLEX_UNIT_MM, ' + round1(parseFloat(num) * RATIO));
src = src.replace(/AutoSizeUtils\.mm2px\(this,\s*(\d+(?:\.\d+)?)\)/g, (m, num) =>
  'AutoSizeUtils.mm2px(this, ' + round1(parseFloat(num) * RATIO) + 'f)');
if (jBefore !== src) {
  fs.writeFileSync(JAVA, src);
  console.log('HomeActivity.java: 已同步');
}

fs.writeFileSync(LOCK, new Date().toISOString() + ' ratio=' + RATIO);
console.log('合计', total, '处，已完成');
