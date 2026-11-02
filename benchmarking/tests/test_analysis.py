"""Run: python -m unittest discover -s benchmarking/tests"""
import importlib.util
import json
import shlex
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import numpy as np

spec = importlib.util.spec_from_file_location('analysis', Path(__file__).parents[1] / 'analyze.py')
a = importlib.util.module_from_spec(spec)
spec.loader.exec_module(a)


class AnalysisTests(unittest.TestCase):
    def jmh(self, label='A', forks=((100., 2., 4.), (200., 4., 6.))):
        return dict(label=label, file=label+'.json', kind='jmh', unit='ms/op', operation='test', parameters={},
                    inferential=True, forks=[np.array(f) for f in forks])

    def http(self):
        return dict(label='HTTP', file='http.json', kind='http', unit='ms', operation='component', inferential=False,
                    parameters={'warmup': '5', 'seconds': '10'}, x=np.array([0., 4., 5., 10.]),
                    series=np.array([100., 80., 20., 40.]), success=np.array([True, False, True, True]),
                    phases=np.array(['warmup', 'warmup', 'measurement', 'measurement']))

    def test_cutoff_keeps_each_retained_iteration_without_averaging_forks(self):
        d = self.jmh()
        a.select_samples(d, 1)
        np.testing.assert_allclose(d['samples'], [2., 4., 4., 6.])
        s = a.summary_stats(d)
        self.assertEqual(s['n'], 4)
        self.assertEqual(s['mean'], 4.)
        self.assertAlmostEqual(s['p90'], 5.4)
        self.assertIsNotNone(s['mean_ci95'])
        self.assertEqual(s['sample_unit'], 'single-shot invocation')
        self.assertEqual(s['fork_count'], 2)
        np.testing.assert_array_equal(d['series'], [100., 2., 4., 200., 4., 6.])
        for cutoff in (-1, 3, 1.5, float('nan')):
            with self.assertRaises(ValueError):
                a.select_samples(d, cutoff)

    def test_jmh_load_and_statistics_use_every_single_shot(self):
        raw = [[100., 2., 4.], [200., 4., 6.]]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)/'jmh.json'
            path.write_text(json.dumps([dict(benchmark='example.Risk.evaluate', mode='ss', threads=1, measurementBatchSize=1, forks=2,
                measurementIterations=3, primaryMetric={'scoreUnit': 'ms/op', 'rawData': raw})]))
            data = a.load(path)
            np.testing.assert_array_equal(data['samples'], [100., 2., 4., 200., 4., 6.])
            a.select_samples(data, 0)
            summary = a.summary_stats(data)
            self.assertEqual(summary['n'], 6)
            self.assertEqual(summary['p90'], 150.)
            self.assertAlmostEqual(summary['sd'], np.std([100., 2., 4., 200., 4., 6.], ddof=1))

    def test_only_single_shot_unit_batches_and_one_thread_are_accepted(self):
        valid = dict(benchmark='example.Risk.evaluate', mode='ss', threads=1,
                     measurementBatchSize=1, forks=2, measurementIterations=3,
                     primaryMetric={'scoreUnit': 'ms/op', 'rawData': [[1., 2., 3.], [4., 5., 6.]]})
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)/'result.json'
            for changes in ({'mode': 'avgt'}, {'mode': 'sample'}, {'mode': 'thrpt'},
                            {'threads': 2}, {'measurementBatchSize': 2},
                            {'threads': None}, {'measurementBatchSize': None}):
                with self.subTest(changes=changes):
                    path.write_text(json.dumps([dict(valid, **changes)]))
                    with self.assertRaisesRegex(ValueError, 'single-shot'):
                        a.load(path)

    def test_readme_uses_the_supplied_single_shot_measurement_policy(self):
        lab = Path(__file__).parents[1]
        readme_commands = [line for line in (lab/'README.md').read_text().splitlines() if line.startswith('java -jar ')]
        self.assertEqual(len(readme_commands), 2)
        for command in readme_commands:
            args = shlex.split(command)
            for flag, expected in [('-bm', 'ss'), ('-bs', '1'), ('-t', '1'), ('-wi', '0'), ('-f', '5'), ('-i', '1000')]:
                self.assertEqual(args[args.index(flag)+1], expected)
            self.assertIn('-p', args)
            self.assertIn('ruleCount=100000', args)
            self.assertNotIn('-w', args)
            self.assertNotIn('-r', args)

    def test_implementation_comparison_requires_same_work_and_input(self):
        x, y = self.jmh(), self.jmh('B')
        for d, implementation in [(x, 'baseline'), (y, 'optimized')]:
            d['parameters'] = dict(implementation=implementation, ruleCount='100000', inputId='risk-rules-v1')
        a.validate_implementation_comparison([x, y])
        for key, value in [('ruleCount', '500000'), ('inputId', 'other')]:
            original = y['parameters'][key]
            y['parameters'][key] = value
            with self.assertRaisesRegex(ValueError, 'keep rule count'):
                a.validate_implementation_comparison([x, y])
            y['parameters'][key] = original
        x, y = self.http(), self.http()
        for d, implementation in [(x, 'baseline'), (y, 'optimized')]:
            d['parameters'].update(implementation=implementation, rule_count='100000', input_id='risk-rules-v1', vus='1')
        a.validate_implementation_comparison([x, y])
        y['parameters']['vus'] = '8'
        with self.assertRaises(ValueError):
            a.validate_implementation_comparison([x, y])
        y['parameters']['vus'] = '1'
        y['parameters']['timing'] = 'http-duration-ms'
        with self.assertRaisesRegex(ValueError, 'timing method'):
            a.validate_comparisons([x, y])

    def test_http_risk_metadata_identifies_implementation_and_new_operation(self):
        with tempfile.TemporaryDirectory() as directory:
            paths = []
            for implementation in ('baseline', 'optimized'):
                config = dict(target='component', vus='1', seconds='1', warmup='0', implementation=implementation,
                              rule_count='100000', input_id='risk-rules-v1', operation='risk-evaluation', timing='http-duration-ms')
                rows = [dict(type='Point', metric='operation_ms', data=dict(time='2026-10-29T00:00:00Z', value=10,
                        tags=dict(config, phase='measurement', success='true', elapsed_start_ms='0'))),
                        dict(type='Point', metric='experiment_elapsed_ms', data=dict(value=1000, tags=config))]
                path = Path(directory)/(implementation+'.json')
                path.write_text(''.join(json.dumps(row)+'\n' for row in rows))
                paths.append(path)
            loaded = [a.load(path) for path in paths]
            self.assertEqual(loaded[0]['operation'], 'risk-evaluation')
            self.assertEqual(loaded[1]['parameters']['implementation'], 'optimized')
            self.assertEqual(loaded[1]['parameters']['timing'], 'http-duration-ms')
            a.validate_implementation_comparison(loaded)

    def test_different_workload_versions_cannot_be_compared(self):
        first, second = self.jmh('first'), self.jmh('second')
        first['parameters'] = dict(implementation='baseline', ruleCount='100000', inputId='risk-rules-v1')
        second['parameters'] = dict(implementation='baseline', ruleCount='100000', inputId='risk-rules-v2')
        with self.assertRaisesRegex(ValueError, 'workload'):
            a.validate_comparisons([first, second])

    def test_jmh_plots_concatenate_forks_and_mark_each_excluded_prefix(self):
        x, y = self.jmh(), self.jmh('B', ((9., 8., 7., 6.), (5., 4., 3., 2.)))
        for d in (x, y):
            a.select_samples(d, 1)
        figures = {}
        def save(figure, path, **kwargs):
            figures[Path(path).name] = figure
        with tempfile.TemporaryDirectory() as directory, patch.object(a.plt.Figure, 'savefig', save):
            a.plots([x, y], Path(directory))
        axis = figures['sequence-1.png'].axes[0]
        traces = [line for line in axis.lines if line.get_linestyle() == '-']
        np.testing.assert_array_equal(np.concatenate([line.get_xdata() for line in traces]), [1, 2, 3, 4, 5, 6])
        np.testing.assert_array_equal(np.concatenate([line.get_ydata() for line in traces]), [100., 2., 4., 200., 4., 6.])
        self.assertEqual(len(traces), 2)
        # Forks keep their sample order while their shades alternate within an experiment.
        self.assertFalse(np.allclose(traces[0].get_color(), traces[1].get_color()))
        self.assertTrue(np.all(np.asarray(traces[1].get_color()) > np.asarray(traces[0].get_color())))
        boundaries = [line.get_xdata()[0] for line in axis.lines if line.get_label() == 'JVM fork boundary']
        self.assertEqual(boundaries, [3.5])
        cutoffs = [line.get_xdata()[0] for line in axis.lines if a.matplotlib.colors.to_hex(line.get_color()) == '#bf3b28']
        self.assertEqual(cutoffs, [1.5, 4.5])
        joint = figures['sequence.png'].axes[0]
        traces = [line for line in joint.lines if line.get_linestyle() == '-']
        self.assertEqual(len(traces), 4)
        self.assertEqual(sum(len(line.get_ydata()) for line in traces[:2]), 6)
        self.assertEqual(sum(len(line.get_ydata()) for line in traces[2:]), 8)
        self.assertFalse(np.allclose(traces[0].get_color(), traces[2].get_color()))
        self.assertFalse(np.allclose(traces[2].get_color(), traces[3].get_color()))

    def test_warmup_closeup_uses_original_first_ten_percent_without_comparison(self):
        x = self.jmh(forks=(tuple(range(1, 12)), tuple(range(101, 112))))
        y = self.jmh('B', ((50., 60., 70., 80.),))
        for d in (x, y):
            a.select_samples(d, 3)
        retained = x['samples'].copy()
        figures = {}
        def save(figure, path, **kwargs):
            figures[Path(path).name] = figure
        with tempfile.TemporaryDirectory() as directory, patch.object(a.plt.Figure, 'savefig', save):
            out = Path(directory)
            gallery = a.plots([x, y], out)
            self.assertEqual([g['kind'] for g in gallery], ['distribution']*3 + ['sequence']*3 + ['warmup']*2)
            self.assertEqual([g['title'] for g in gallery if g['kind'] == 'warmup'], ['A', 'B'])
            axes = figures['warmup-closeup-1.png'].axes[0]
            traces = [line for line in axes.lines if line.get_linestyle() == '-']
            np.testing.assert_array_equal(np.concatenate([line.get_ydata() for line in traces]), [1, 2, 101, 102])
            np.testing.assert_array_equal(np.concatenate([line.get_xdata() for line in traces]), [1, 2, 3, 4])
            self.assertFalse(np.allclose(traces[0].get_color(), traces[1].get_color()))
            self.assertEqual([text.get_text() for text in axes.texts], ['Fork 1', 'Fork 2'])
            # A short fork still contributes one visible point.
            np.testing.assert_array_equal(figures['warmup-closeup-2.png'].axes[0].lines[0].get_ydata(), [50.])
            summaries = [a.summary_stats(d) for d in (x, y)]
            pairs = a.comparisons([x, y])
            a.save_page(out, summaries, pairs, [], a.conclusions(summaries, pairs), gallery)
            page = (out/'index.html').read_text()
            self.assertEqual(page.count('<figcaption>'), 8)
            self.assertGreater(page.index('<h2>Warm-up close-up</h2>'), page.index('src="sequence.png"'))
            self.assertIn('regardless of the selected cutoff', page)
        np.testing.assert_array_equal(x['samples'], retained)

    def test_closeup_percentage_changes_jmh_window_and_validates_range(self):
        d = self.jmh(forks=(tuple(range(1, 21)),))
        a.select_samples(d, 10)
        figures = {}
        def save(figure, path, **kwargs):
            figures[Path(path).name] = figure
        with tempfile.TemporaryDirectory() as directory, patch.object(a.plt.Figure, 'savefig', save):
            for percent, expected in [(25, list(range(1, 6))), (100, list(range(1, 21)))]:
                a.plots([d], Path(directory), percent)
                axis = figures['warmup-closeup-1.png'].axes[0]
                np.testing.assert_array_equal(axis.lines[0].get_ydata(), expected)
                self.assertIn(f'first {percent}%', axis.get_title())
            for percent in (0, -1, 101, float('nan'), float('inf')):
                with self.assertRaises(ValueError):
                    a.plots([d], Path(directory), percent)
        np.testing.assert_array_equal(d['samples'], list(range(11, 21)))

    def test_http_closeups_use_run_time_and_include_early_successes_and_failures(self):
        figures = {}
        def save(figure, path, **kwargs):
            figures[Path(path).name] = figure
        with tempfile.TemporaryDirectory() as directory, patch.object(a.plt.Figure, 'savefig', save):
            for operation in ('component', 'end-to-end'):
                d = self.http()
                d['operation'] = operation
                a.select_samples(d, 5)
                gallery = a.plots([d], Path(directory), 50)
                self.assertEqual([item['kind'] for item in gallery], ['distribution', 'sequence', 'warmup'])
                axis = figures['warmup-closeup-1.png'].axes[0]
                np.testing.assert_array_equal(axis.collections[0].get_offsets(), [[0, 100], [5, 20]])
                np.testing.assert_array_equal(axis.collections[1].get_offsets(), [[4, 80]])
                self.assertEqual(axis.get_xlim(), (0, 7.5))
                self.assertEqual(a.summary_stats(d)['mean'], 30)
                a.plots([d], Path(directory), 100)
                self.assertEqual(sum(len(c.get_offsets()) for c in figures['warmup-closeup-1.png'].axes[0].collections), 4)
            d['x'] = d['x'] + 1
            a.plots([d], Path(directory), .01)
            axis = figures['warmup-closeup-1.png'].axes[0]
            self.assertIn('No requests started', axis.texts[0].get_text())

    def test_http_warmup_selection_and_failure_denominator(self):
        d = self.http()
        a.select_samples(d)
        self.assertEqual(a.summary_stats(d)['warmup_cutoff'], 0)
        self.assertAlmostEqual(a.summary_stats(d)['mean'], 160/3)
        a.select_samples(d, 5)
        self.assertEqual(a.summary_stats(d)['mean'], 30.)
        a.select_samples(d, 0)
        s = a.summary_stats(d)
        self.assertAlmostEqual(s['mean'], 160/3)
        self.assertEqual(s['failure_fraction'], .25)
        self.assertEqual(s['successful_ops_per_s'], 3/15)
        self.assertIsNotNone(s['mean_ci95'])
        self.assertEqual(len(d['series']), 4)
        with self.assertRaises(ValueError):
            a.select_samples(d, 15)

    def test_http_tests_use_samples_with_explicit_independence_assumption(self):
        x, y = self.http(), self.http()
        y['label'] = 'B'
        for d in (x, y):
            a.select_samples(d, 0)
        pair = a.comparisons([x, y])[0]
        self.assertAlmostEqual(pair['welch_p'], 1.)
        self.assertAlmostEqual(pair['mann_whitney_p'], 1.)
        rows = a.conclusions([a.summary_stats(x), a.summary_stats(y)], [pair])[0]['rows']
        self.assertIn('assuming independence', next(row['interpretation'] for row in rows if row['method'] == 'Welch two-sample t-test'))

    def test_speedup_is_distinct_from_time_reduction(self):
        x, y = self.jmh(forks=((2., 2.), (2., 2.))), self.jmh('B', ((1., 1.), (1., 1.)))
        for d in (x, y):
            a.select_samples(d)
        pair = a.comparisons([x, y])[0]
        self.assertEqual(pair['time_ratio_a_over_b'], 2.)
        self.assertEqual(pair['time_reduction_percent'], 50.)
        rows = a.conclusions([a.summary_stats(x), a.summary_stats(y)], [pair])[0]['rows']
        self.assertIn('50% less', next(row['interpretation'] for row in rows if row['method'] == 'Speedup (mean ratio A/B)'))

    def test_all_failed_requests_and_comparison_plot_order(self):
        x, y = self.http(), self.http()
        x['success'][:] = False
        y['label'] = '<B>'
        for d in (x, y):
            a.select_samples(d, 5)
        self.assertIsNone(a.summary_stats(x)['mean'])
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory)
            gallery = a.plots([x, y], out)
            self.assertEqual([g['kind'] for g in gallery], ['distribution']*3 + ['sequence']*3 + ['warmup']*2)
            self.assertEqual([g['title'] for g in gallery[:3]], ['HTTP', '<B>', 'Joint comparison'])
            summaries = [a.summary_stats(d) for d in (x,y)]
            pairs = a.comparisons([x,y])
            a.save_page(out, summaries, pairs, [], a.conclusions(summaries, pairs), gallery)
            page = (out/'index.html').read_text()
            self.assertIn('&lt;B&gt;', page)
            self.assertNotIn('<B>', page)
            self.assertEqual(len(list(out.glob('*.png'))), 8)
            self.assertEqual(page.count('<figcaption>'), 8)
            self.assertIn('probability density', page)
            self.assertIn('0.9 means 90%', page)
            self.assertIn('50% less', page)
            self.assertIn('Analysis generated:', page)


if __name__ == '__main__':
    unittest.main()
