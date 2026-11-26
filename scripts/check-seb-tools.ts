import assert from 'node:assert/strict';
import { SebFile } from '../src/seb-tools.ts';

const xml = `<?xml version="1.0" encoding="UTF-8"?>
<plist version="1.0">
<dict>
  <key>originatorVersion</key>
  <string>SEB_Windows_3.7.0</string>
  <key>startURL</key>
  <string>https://exam.example.com/quiz?a=1&amp;b=2</string>
  <key>allowQuit</key>
  <true/>
  <key>examMode</key>
  <integer>1</integer>
  <key>threshold</key>
  <real>0.5</real>
  <key>emptyDict</key>
  <dict/>
  <key>urlFilterRules</key>
  <array>
    <dict>
      <key>expression</key>
      <string>example.com</string>
      <key>active</key>
      <true/>
    </dict>
  </array>
  <key>certificate</key>
  <data>
    aGVsbG8=
  </data>
  <key>created</key>
  <date>2026-01-01T00:00:00Z</date>
</dict>
</plist>`;

const seb = await SebFile.createInstance(xml);
assert.ok(seb, 'parse failed');

assert.equal(seb.StartUrl, 'https://exam.example.com/quiz?a=1&b=2');
assert.equal(
  seb.SerializedJson,
  '{"allowQuit":true,"certificate":"aGVsbG8=","created":"2026-01-01T00:00:00Z","examMode":1,"startURL":"https://exam.example.com/quiz?a=1&b=2","threshold":0.5,"urlFilterRules":[{"active":true,"expression":"example.com"}]}'
);
assert.doesNotThrow(() => JSON.parse(seb.SerializedJson), 'serialized config must be valid JSON');
assert.match(seb.ConfigHash, /^[0-9a-f]{64}$/);
assert.equal(seb.getConfigKey('https://exam.example.com/quiz'), seb.getConfigKey('https://exam.example.com/quiz'));
assert.equal(seb.getConfigKey('https://exam.example.com/quiz#section'), seb.getConfigKey('https://exam.example.com/quiz'));
assert.notEqual(seb.getConfigKey('https://exam.example.com/quiz'), seb.getConfigKey('https://exam.example.com/other'));

const escaped = await SebFile.createInstance(`<dict><key>title</key><string>a "quoted" \\ path</string></dict>`);
assert.ok(escaped);
assert.equal(JSON.parse(escaped.SerializedJson).title, 'a "quoted" \\ path');

assert.equal(await SebFile.createInstance('<not-a-dict/>'), undefined);

console.log('seb-tools checks passed');
