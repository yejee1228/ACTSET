// 빌드 산출물을 Spring Boot static/에 내장한다(docs/09 "Spring Boot에 내장(권장)").
import { cpSync, rmSync, existsSync, mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const src = path.resolve(here, '..', 'dist');
const dest = path.resolve(here, '..', '..', 'backend', 'src', 'main', 'resources', 'static');

if (!existsSync(src)) {
  console.error('dist/ 가 없습니다. 먼저 npm run build를 실행하세요.');
  process.exit(1);
}

if (existsSync(dest)) {
  rmSync(dest, { recursive: true, force: true });
}
mkdirSync(dest, { recursive: true });
cpSync(src, dest, { recursive: true });
console.log(`복사 완료: ${src} -> ${dest}`);
