import importlib.util
import json
from pathlib import Path
import tempfile
import threading
from http.server import ThreadingHTTPServer
from types import SimpleNamespace
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import urlopen

spec = importlib.util.spec_from_file_location('viewer', Path(__file__).parents[1] / 'container' / 'results_server.py')
v = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v)


class ViewerTests(unittest.TestCase):
    def test_invalid_selection_returns_readable_error_without_modifying_report(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root/'results').mkdir()
            (root/'results'/'sample.json').write_text('[]')
            report = root/'analysis'/'test'
            report.mkdir(parents=True)
            config = json.dumps({'experiments': [{'file': 'results/sample.json'}]})
            (report/'stats.json').write_text(config)
            (report/'index.html').write_text('report')
            class Handler(v.Handler):
                analyzer = Path('unused')
                def log_message(self, *args):
                    pass
            def handler(*args, **kwargs):
                return Handler(*args, directory=directory, **kwargs)
            server = ThreadingHTTPServer(('127.0.0.1', 0), handler)
            worker = threading.Thread(target=server.serve_forever, daemon=True)
            worker.start()
            try:
                result = SimpleNamespace(returncode=2, stderr='Invalid fork’s cutoff.')
                with patch.object(v.subprocess, 'run', return_value=result):
                    with self.assertRaises(HTTPError) as failure:
                        urlopen(f'http://127.0.0.1:{server.server_port}/reanalyze', urlencode({'report': '/analysis/test', 'cutoff': '3'}).encode())
                    self.assertEqual(failure.exception.code, 400)
                    self.assertIn('fork’s cutoff', failure.exception.read().decode())
                self.assertEqual((report/'stats.json').read_text(), config)
            finally:
                server.shutdown()
                server.server_close()
                worker.join()


class DashboardTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        (self.root/'results').mkdir()
        (self.root/'analysis').mkdir()
        for name, benchmark, factor in [('A','example.Risk.evaluate',1), ('B','example.Risk.evaluate',2), ('other','example.Other.evaluate',1)]:
            (self.root/'results'/f'{name}.json').write_text(json.dumps([dict(
                benchmark=benchmark, mode='ss', threads=1, measurementBatchSize=1, forks=2, measurementIterations=3, params={'ruleCount': str(factor)},
                primaryMetric={'scoreUnit':'ms/op','rawData':[[factor*x for x in [5,2,3]],[factor*x for x in [6,3,4]]]})]))
        directory = str(self.root)
        class Handler(v.Handler):
            analyzer = Path(__file__).parents[1]/'analyze.py'
            def log_message(self, *args):
                pass
        self.server = ThreadingHTTPServer(('127.0.0.1',0),lambda *args,**kw: Handler(*args,directory=directory,**kw))
        self.worker = threading.Thread(target=self.server.serve_forever,daemon=True)
        self.worker.start()
        self.url=f'http://127.0.0.1:{self.server.server_port}'

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.worker.join()
        self.temp.cleanup()

    def post(self,path,fields):
        return urlopen(self.url+path,urlencode(fields,doseq=True).encode())

    def test_catalogue_analysis_comparison_and_independent_deletion(self):
        page=urlopen(self.url).read().decode()
        self.assertIn('Result saved (UTC)',page)
        self.assertIn('type="checkbox"',page)
        self.assertIn('Analyze',page)
        response=self.post('/analyze',{'experiment':'results/A.json'})
        self.assertIn('Summary of conclusions',response.read().decode())
        single=self.root/response.url.split(self.url+'/')[1].removesuffix('/index.html')
        response=self.post('/analyze',{'experiment':['results/A.json','results/B.json']})
        comparison=self.root/response.url.split(self.url+'/')[1].removesuffix('/index.html')
        data=json.loads((comparison/'stats.json').read_text())
        self.assertIn('generated_at',data)
        self.assertEqual([s['warmup_cutoff'] for s in data['experiments']],[0,0])
        self.assertTrue(all(s['saved_at'] for s in data['experiments']))
        self.assertIn(data['generated_at'],urlopen(self.url).read().decode())
        # Reanalysis must accept absolute source paths stored by dashboard analyses.
        self.post('/reanalyze',{'report':str(comparison.relative_to(self.root)),'cutoff':'1'}).read()
        self.assertEqual(json.loads((comparison/'stats.json').read_text())['experiments'][0]['warmup_cutoff'],1)
        self.post('/delete-analysis',{'report':str(single.relative_to(self.root))}).read()
        self.assertFalse(single.exists())
        self.assertTrue((self.root/'results/A.json').exists())
        self.post('/delete-experiments',{'experiment':'results/A.json'}).read()
        self.assertFalse((self.root/'results/A.json').exists())
        self.assertTrue(comparison.exists())
        self.assertTrue((self.root/'results/B.json').exists())
        with self.assertRaises(HTTPError) as error:
            self.post('/reanalyze',{'report':str(comparison.relative_to(self.root)),'cutoff':'0'})
        self.assertEqual(error.exception.code,400)

    def test_incompatible_comparison_does_not_leave_a_report(self):
        with self.assertRaises(HTTPError) as error:
            self.post('/analyze',{'experiment':['results/A.json','results/other.json']})
        self.assertEqual(error.exception.code,400)
        self.assertIn('same scope/operation',error.exception.read().decode())
        self.assertEqual(list((self.root/'analysis').iterdir()),[])

    def test_deletion_checks_all_paths_before_removing_anything(self):
        for path,fields in [('/delete-experiments',{'experiment':['results/A.json','../outside.json']}),
                            ('/delete-analysis',{'report':'analysis'}),
                            ('/delete-analysis',{'report':'../outside'})]:
            with self.assertRaises(HTTPError) as error:
                self.post(path,fields)
            self.assertEqual(error.exception.code,400)
        self.assertTrue((self.root/'results/A.json').exists())


    def test_bulk_analysis_removal_preserves_raw_files_and_unselected_reports(self):
        for name in ('first', 'second', 'keep'):
            report = self.root/'analysis'/name
            report.mkdir()
            (report/'index.html').write_text('report')
            (report/'stats.json').write_text(json.dumps({'experiments': []}))
        page = urlopen(self.url).read().decode()
        self.assertIn('Select all experiments', page)
        self.assertIn('Select all analyses', page)
        self.assertIn('form="analyses" type="checkbox"', page)
        self.assertIn('Remove selected analyses', page)
        for values in (['analysis/first', '../outside'], []):
            with self.assertRaises(HTTPError) as error:
                self.post('/delete-analysis', {'report': values, 'unused': '1'})
            self.assertEqual(error.exception.code, 400)
            self.assertTrue((self.root/'analysis/first').exists())
        self.post('/delete-analysis', {'report': ['analysis/first', 'analysis/second', 'analysis/first']}).read()
        self.assertFalse((self.root/'analysis/first').exists())
        self.assertFalse((self.root/'analysis/second').exists())
        self.assertTrue((self.root/'analysis/keep').exists())
        self.assertEqual(len(list((self.root/'results').glob('*.json'))), 3)


    def test_closeup_percentage_persists_and_invalid_updates_preserve_report(self):
        response = self.post('/analyze', {'experiment': 'results/A.json'})
        report = self.root/response.url.split(self.url+'/')[1].removesuffix('/index.html')
        before = json.loads((report/'stats.json').read_text())
        self.assertEqual(before['closeup_percent'], 10)
        form = {'report': str(report.relative_to(self.root)), 'cutoff': '0', 'closeup_percent': '50'}
        response = self.post('/reanalyze', form)
        self.assertIn('first 50%', response.read().decode())
        after = json.loads((report/'stats.json').read_text())
        self.assertEqual(after['closeup_percent'], 50)
        self.assertEqual(after['experiments'], before['experiments'])
        del form['closeup_percent']
        form['cutoff'] = '1'
        self.post('/reanalyze', form).read()
        self.assertEqual(json.loads((report/'stats.json').read_text())['closeup_percent'], 50)
        saved = (report/'stats.json').read_bytes()
        for percent in ('0', '101', 'nan'):
            form['closeup_percent'] = percent
            with self.assertRaises(HTTPError) as error:
                self.post('/reanalyze', form)
            self.assertEqual(error.exception.code, 400)
            self.assertEqual((report/'stats.json').read_bytes(), saved)
