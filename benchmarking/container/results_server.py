#!/usr/bin/env python3
"""Local experiment catalogue and analysis viewer; never launches benchmarks."""
import argparse
from datetime import datetime, timezone
import html
import json
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import math
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import threading
from urllib.parse import parse_qs, quote, urlsplit


ANALYSIS_LOCK = threading.Lock()


def timestamp(path):
    return datetime.fromtimestamp(path.stat().st_mtime, timezone.utc).isoformat(timespec='seconds')


def experiment_description(path):
    """Read just enough metadata to label a run; the analyzer validates the full export."""
    try:
        with path.open(encoding='utf-8') as stream:
            first = next((line for line in stream if line.strip()), '')
            stream.seek(0)
            if first.lstrip().startswith('['):
                items = json.load(stream)
                if len(items) != 1:
                    return 'JMH: multiple configurations (export separately)'
                item = items[0]
                if item.get('mode') != 'ss' or item.get('threads') != 1 or item.get('measurementBatchSize') != 1:
                    return 'JMH export needs rerun: use -bm ss -bs 1 -t 1 -tu ms'
                return 'JMH single-shot · ' + item['benchmark'].split('.')[-2] + ' · ' + json.dumps(item.get('params', {}))
            for line in stream:
                point = json.loads(line)
                if point.get('type') == 'Point' and point.get('metric') == 'operation_ms':
                    tags = point['data']['tags']
                    return f"{tags['target']} · {tags['implementation']} · rules {tags['rule_count']} · {tags['vus']} clients · {tags['seconds']} s · configured warm-up {tags['warmup']} s"
    except (ValueError, KeyError, IndexError, TypeError):
        pass
    return 'Unrecognized or incomplete export'


class Handler(SimpleHTTPRequestHandler):
    def end_headers(self):
        self.send_header('Cache-Control', 'no-store')
        super().end_headers()

    def data_path(self, value, category, report=False):
        root = Path(self.directory).resolve()
        incoming = Path(value)
        path = incoming.resolve() if incoming.is_absolute() and incoming.is_relative_to(root) else (root / value.lstrip('/')).resolve()
        base = (root / category).resolve()
        if not base.is_relative_to(root) or path == base or not path.is_relative_to(base):
            raise ValueError('Choose an existing experiment or analysis from the dashboard.')
        if report:
            if not path.is_dir() or not (path / 'index.html').is_file() or not (path / 'stats.json').is_file():
                raise ValueError('Analysis report not found.')
        elif not path.is_file() or path.suffix != '.json':
            raise ValueError('The raw experiment JSON file was not found in results/.')
        return path

    def run_analysis(self, files, report, cutoff, closeup_percent=10):
        result = subprocess.run([sys.executable, str(self.analyzer), *map(str, files), '--warmup-cutoff', str(cutoff), '--closeup-percent', str(closeup_percent), '-o', str(report)],
                                cwd=Path(self.directory).resolve(), capture_output=True, text=True, timeout=120)
        if result.returncode:
            raise ValueError(result.stderr.strip()[-1500:])

    def redirect(self, path='/'):
        self.send_response(303)
        self.send_header('Location', path)
        self.end_headers()

    def do_POST(self):
        actions = ('/analyze', '/reanalyze', '/delete-experiments', '/delete-analysis')
        if self.path not in actions:
            return self.send_error(404)
        if self.headers.get('Origin') and urlsplit(self.headers['Origin']).netloc != self.headers.get('Host'):
            return self.send_error(403, 'Use the form on this results server.')
        acquired = False
        try:
            size = int(self.headers.get('Content-Length', '0'))
            if not 0 < size <= 32768:
                raise ValueError('Invalid form size.')
            form = parse_qs(self.rfile.read(size).decode())
            root = Path(self.directory).resolve()
            acquired = ANALYSIS_LOCK.acquire(blocking=False)
            if not acquired:
                return self.send_error(409, 'Another dashboard operation is running. Go back and try again shortly.')
            if self.path == '/delete-analysis':
                values = form.get('report', [])
                if not values:
                    raise ValueError('Select at least one analysis.')
                reports = list(dict.fromkeys(self.data_path(value, 'analysis', report=True) for value in values))
                # Validate every selection first; a selected parent already contains its children.
                for report in reports:
                    if not any(parent in report.parents for parent in reports):
                        shutil.rmtree(report)
                return self.redirect()
            if self.path in ('/analyze', '/delete-experiments'):
                values = form.get('experiment', [])
                if not values:
                    raise ValueError('Select at least one experiment.')
                files = list(dict.fromkeys(self.data_path(v, 'results') for v in values))
                if self.path == '/delete-experiments':
                    for file in files:
                        file.unlink()
                    return self.redirect()
                if len(files) > 8:
                    raise ValueError('Compare at most eight experiments at a time.')
                (root / 'analysis').mkdir(parents=True, exist_ok=True)
                prefix = ('comparison-' if len(files) > 1 else 'experiment-') + datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ-')
                report = Path(tempfile.mkdtemp(prefix=prefix, dir=root / 'analysis'))
                try:
                    self.run_analysis(files, report, 0)
                except Exception:
                    shutil.rmtree(report)
                    raise
            else:
                cutoff = float(form['cutoff'][0])
                if not math.isfinite(cutoff) or cutoff < 0:
                    raise ValueError('Cutoff must be finite and nonnegative.')
                report = self.data_path(form['report'][0], 'analysis', report=True)
                config = json.loads((report / 'stats.json').read_text())
                files = [self.data_path(e['file'], 'results') for e in config['experiments']]
                percent = float(form.get('closeup_percent', [config.get('closeup_percent', 10)])[0])
                if not math.isfinite(percent) or not 0 < percent <= 100:
                    raise ValueError('Close-up percentage must be greater than 0 and at most 100.')
                self.run_analysis(files, report, cutoff, percent)
            self.redirect('/' + quote(report.relative_to(root).as_posix()) + '/index.html')
        except (ValueError, KeyError, IndexError, OSError, subprocess.TimeoutExpired) as error:
            self.send_error(400, 'Invalid dashboard operation', explain=str(error) + ' Go back to adjust your selection.')
        finally:
            if acquired:
                ANALYSIS_LOCK.release()

    def dashboard(self):
        root = Path(self.directory).resolve()
        def esc(value):
            return html.escape(str(value), quote=True)
        experiments = sorted((root / 'results').rglob('*.json'), key=lambda p: p.stat().st_mtime, reverse=True)
        rows = []
        for path in experiments:
            # Do not offer symlinks outside the results directory as dashboard actions.
            if not path.resolve().is_relative_to((root / 'results').resolve()):
                continue
            value = esc(path.relative_to(root).as_posix())
            label = esc(path.relative_to(root / 'results'))
            rows.append(f'''<tr><td><input form="experiments" type="checkbox" name="experiment" value="{value}" aria-label="Select {label}"></td>
<td><a href="/{quote(path.relative_to(root).as_posix())}">{label}</a></td><td><time>{timestamp(path)}</time></td><td>{esc(experiment_description(path))}</td>
<td><form method="post" action="/analyze"><input type="hidden" name="experiment" value="{value}"><button>Analyze</button></form></td></tr>''')
        reports = sorted((root / 'analysis').rglob('index.html'), key=lambda p: p.stat().st_mtime, reverse=True)
        analyses = []
        for index in reports:
            report = index.parent
            if not report.resolve().is_relative_to((root / 'analysis').resolve()):
                continue
            saved, names = timestamp(index), []
            try:
                data = json.loads((report / 'stats.json').read_text())
                saved = data.get('generated_at') or saved
                for item in data['experiments']:
                    date = item.get('saved_at')
                    source = Path(item['file'])
                    # Use the raw file timestamp if report metadata has no saved date.
                    candidate = source if source.is_absolute() else root / source
                    if not date and candidate.is_file() and candidate.resolve().is_relative_to((root / 'results').resolve()):
                        date = timestamp(candidate)
                    names.append(esc(item['experiment']) + ('<br><small>Result saved: ' + esc(date) + '</small>' if date else ''))
            except (OSError, ValueError, KeyError, TypeError):
                names = ['Report metadata unavailable']
            value = esc(report.relative_to(root).as_posix())
            analyses.append(f'''<tr><td><input form="analyses" type="checkbox" name="report" value="{value}" aria-label="Select analysis {esc(report.relative_to(root / 'analysis'))}"></td><td><a href="/{quote(index.relative_to(root).as_posix())}">{esc(report.relative_to(root / 'analysis'))}</a></td>
<td><time>{esc(saved)}</time></td><td>{'<br>'.join(names)}</td><td><form method="post" action="/delete-analysis" onsubmit="return confirm('Remove this analysis? Its experiment files will be kept.')">
<input type="hidden" name="report" value="{value}"><button>Remove analysis</button></form></td></tr>''')
        return f'''<!doctype html><html lang="en"><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>Benchmark results</title>
<style>body{{font:16px system-ui;max-width:1400px;margin:32px auto;padding:0 20px;color:#18202b}}a{{color:#1758aa}}
.scroll{{overflow-x:auto}}table{{border-collapse:collapse;width:100%}}th,td{{border:1px solid #ddd;padding:10px;text-align:left;vertical-align:top}}
th{{background:#f1f4f8}}button{{font:inherit;padding:7px 10px;cursor:pointer}}form{{margin:0}}small{{color:#536174}}td{{overflow-wrap:anywhere}}time{{font-size:14px}}.actions{{display:flex;flex-wrap:wrap;gap:12px;margin:15px 0}}</style>
<h1>Benchmark results</h1><p>Run experiments in the container shell, then refresh. Let runs finish before analyzing them. All dates and times are UTC; “result saved” is the raw file’s last modification time.</p>
<p><a href="/">Refresh</a> · <a href="/results/">Raw experiment files</a></p>
<h2>Experiments</h2><p>Analyze one run, or check compatible runs of the same operation and compare them. New analyses start with warm-up cutoff 0.</p>
<form id="experiments" method="post" action="/analyze"></form>
<div class="actions"><button id="select-experiments" type="button">Select all experiments</button><button id="compare" form="experiments" disabled>Compare selected</button>
<button id="remove" form="experiments" disabled formaction="/delete-experiments" onclick="return confirm('Remove the selected raw experiment files? Saved analyses remain viewable, but updating them requires these files.')">Remove selected experiments</button></div>
{'<div class="scroll"><table><thead><tr><th>Select</th><th>Experiment</th><th>Result saved (UTC)</th><th>Configuration</th><th>Action</th></tr></thead><tbody>' + ''.join(rows) + '</tbody></table></div>' if rows else '<p>No experiments yet. Follow the README to run JMH or k6.</p>'}
<h2>Analyses</h2>
<form id="analyses" method="post" action="/delete-analysis" onsubmit="return confirm('Remove the selected analyses? Their experiment files will be kept.')"></form>
<div class="actions"><button id="select-analyses" type="button">Select all analyses</button><button id="remove-analyses" form="analyses" disabled>Remove selected analyses</button></div>{'<div class="scroll"><table><thead><tr><th>Select</th><th>Analysis</th><th>Generated / updated (UTC)</th><th>Experiments and saved times</th><th>Action</th></tr></thead><tbody>' + ''.join(analyses) + '</tbody></table></div>' if analyses else '<p>No analyses yet. Use Analyze or Compare selected above, or run python analyze.py in the shell.</p>'}
<script>
function selectionGroup(form, selectId, removeId, noun, compareId) {{
    const choices = [...document.querySelectorAll('input[type="checkbox"][form="' + form + '"]')];
    const select = document.getElementById(selectId);
    function update() {{
        const count = choices.filter(box => box.checked).length;
        select.disabled = choices.length === 0;
        select.textContent = count && count === choices.length ? 'Clear ' + noun + ' selection' : 'Select all ' + (noun === 'experiment' ? 'experiments' : 'analyses');
        document.getElementById(removeId).disabled = count === 0;
        if (compareId) document.getElementById(compareId).disabled = count < 2;
    }}
    select.addEventListener('click', () => {{
        const checked = !choices.every(box => box.checked);
        choices.forEach(box => {{box.checked = checked;}});
        update();
    }});
    choices.forEach(box => box.addEventListener('change', update));
    update();
}}
selectionGroup('experiments', 'select-experiments', 'remove', 'experiment', 'compare');
selectionGroup('analyses', 'select-analyses', 'remove-analyses', 'analysis');
</script>
<p>Removing an analysis keeps its raw experiments. Removing an experiment keeps saved analyses, but their warm-up update needs the raw file.</p>
</html>'''

    def do_GET(self):
        path = urlsplit(self.path).path
        if path == '/health':
            body = b'OK'
        elif path == '/':
            body = self.dashboard().encode()
        else:
            return super().do_GET()
        self.send_response(200)
        self.send_header('Content-Type', 'text/html; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--directory', type=Path, default=Path('/lab/data'))
    parser.add_argument('--analyzer', type=Path, default=Path('/lab/analyze.py'))
    parser.add_argument('--port', type=int, default=8090)
    args = parser.parse_args()
    Handler.analyzer = args.analyzer.resolve()
    directory = args.directory.resolve()
    def handler(*a, **kw):
        return Handler(*a, directory=str(directory), **kw)
    ThreadingHTTPServer(('0.0.0.0', args.port), handler).serve_forever()
