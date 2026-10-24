import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path


def main(directory: Path) -> None:
    path = directory / 'channels.json'
    report = json.loads(path.read_text(encoding='utf-8'))
    rows = [row for row in report['channels'] if len(row.get('urls', [])) > 1
            and row.get('manifest', {}).get('status') != 'HLS_MANIFEST']
    print(f'Checking one official backup endpoint for {len(rows)} channels', flush=True)
    for row in rows:
        time.sleep(3.5)
        status = None
        error_type = None
        body = b''
        try:
            request = urllib.request.Request(row['urls'][1], headers={
                'User-Agent': 'qqlive', 'Referer': 'https://live.cctv.cn/',
                'Accept': 'application/vnd.apple.mpegurl,application/json,*/*'})
            with urllib.request.urlopen(request, timeout=12) as response:
                status = response.status
                body = response.read(1024 * 1024)
        except urllib.error.HTTPError as error:
            status = error.code
            error_type = type(error).__name__
        except Exception as error:
            error_type = type(error).__name__
        hls = body.lstrip(b'\xef\xbb\xbf \r\n\t').startswith(b'#EXTM3U')
        result = {'checkedAt': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
                  'httpStatus': status, 'status': 'HLS_MANIFEST' if hls else
                  'NOT_HLS' if status and 200 <= status < 300 else
                  'HTTP_ERROR' if status else 'NETWORK_ERROR',
                  'errorType': error_type, 'videoAudio': 'NOT_DECODED'}
        row['backupManifest'] = result
        if hls:
            row['primaryManifest'] = row['manifest']
            row['manifest'] = result
            row['confirmedUrl'] = row['urls'][1]
        temp = path.with_suffix('.json.tmp')
        temp.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        temp.replace(path)
        print(f"{row['name']}: {result['status']} {status or '-'}", flush=True)


if __name__ == '__main__':
    main(Path(sys.argv[1]))
