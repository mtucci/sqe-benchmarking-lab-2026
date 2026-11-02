"""All retained samples drive inference; repetitions remain visible separately."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import numpy as np
from scipy import stats

spec = importlib.util.spec_from_file_location('replicated_analysis', Path(__file__).parents[1] / 'analyze.py')
a = importlib.util.module_from_spec(spec)
spec.loader.exec_module(a)


def write_http_experiment(root, name, means=(10, 12, 14, 16, 18), counts=(1, 2, 3, 4, 5), failed_run=None):
    folder = Path(root) / name
    folder.mkdir(parents=True)
    for run, (mean, count) in enumerate(zip(means, counts), 1):
        tags = dict(target='component', implementation='optimized' if 'optimized' in name else 'baseline',
                    rule_count='100000', input_id='risk-rules-v1', operation='risk-evaluation',
                    timing='http-duration-ms', vus='1', seconds='4', warmup='0', run=str(run), runs=str(len(means)))
        rows = []
        for elapsed, value, success in [(0, 1000, True)] + [(1 + i / count, mean, run != failed_run) for i in range(count)]:
            rows.append(dict(type='Point', metric='operation_ms', data=dict(
                time=f'2026-11-02T00:00:0{int(elapsed)}Z', value=value,
                tags=dict(tags, phase='measurement', success=str(success).lower(), elapsed_start_ms=str(elapsed * 1000)))))
        rows.append(dict(type='Point', metric='experiment_elapsed_ms', data=dict(value=4000, tags=tags)))
        (folder / f'run-{run}.json').write_text(''.join(json.dumps(row) + '\n' for row in rows))
    return folder


class ReplicationTests(unittest.TestCase):
    def test_http_ci_and_mean_use_all_samples_from_five_runs(self):
        with tempfile.TemporaryDirectory() as folder:
            path = write_http_experiment(folder, 'baseline')
            data = a.load(path)
            a.select_samples(data, 1)
            result = a.summary_stats(data)
            self.assertEqual(result['n'], 15)
            self.assertEqual(result['replicate_count'], 5)
            self.assertEqual(result['inference_n'], 15)
            self.assertEqual(result['inference_unit'], 'successful request')
            samples = np.repeat([10, 12, 14, 16, 18], [1, 2, 3, 4, 5])
            self.assertAlmostEqual(result['mean'], samples.mean())
            self.assertNotAlmostEqual(result['mean'], 14)
            half = stats.t.ppf(.975, 14) * samples.std(ddof=1) / np.sqrt(15)
            np.testing.assert_allclose(result['mean_ci95'], [samples.mean() - half, samples.mean() + half])
            self.assertEqual(result['successful_ops_per_s'], 15 / (5 * 3))
            rows = a.replicate_rows([data])
            self.assertEqual([row['sample_count'] for row in rows], [1, 2, 3, 4, 5])

    def test_tests_and_difference_ci_use_individual_retained_samples(self):
        with tempfile.TemporaryDirectory() as folder:
            first = a.load(write_http_experiment(folder, 'baseline'))
            second = a.load(write_http_experiment(folder, 'optimized', means=(5, 7, 9, 11, 13)))
            for item in [first, second]:
                a.select_samples(item, 1)
            pair = a.comparisons([first, second])[0]
            x, y = (np.repeat(values, [1, 2, 3, 4, 5]) for values in (np.arange(10, 20, 2), np.arange(5, 15, 2)))
            self.assertAlmostEqual(pair['welch_p'], stats.ttest_ind(x, y, equal_var=False).pvalue)
            self.assertAlmostEqual(pair['mann_whitney_p'], stats.mannwhitneyu(x, y, alternative='two-sided').pvalue)
            self.assertEqual(pair['inference_n_a'], 15)
            self.assertEqual(pair['inference_n_b'], 15)
            self.assertAlmostEqual(pair['time_ratio_a_over_b'], x.mean() / y.mean())
            expected = stats.t.interval(.95, 28, loc=-5, scale=np.sqrt(x.var(ddof=1) / 15 + y.var(ddof=1) / 15))
            np.testing.assert_allclose(pair['mean_difference_ci95'], expected)
            # Under the declared independence assumption, more observations tighten the CI.
            larger = a.load(write_http_experiment(folder, 'many-baseline', counts=(100, 200, 300, 400, 500)))
            a.select_samples(larger, 1)
            self.assertEqual(a.summary_stats(larger)['inference_n'], 1500)
            self.assertLess(a.summary_stats(larger)['ci95_width'], a.summary_stats(first)['ci95_width'])

    def test_jmh_inference_uses_every_retained_shot(self):
        data = dict(label='jmh', file='jmh.json', kind='jmh', unit='ms/op', operation='test', parameters={},
                    forks=[np.array([1000, mean, mean, mean]) for mean in [10, 12, 14, 16, 18]])
        a.select_samples(data, 1)
        result = a.summary_stats(data)
        self.assertEqual(result['n'], 15)
        self.assertEqual(result['inference_n'], 15)
        self.assertEqual(result['inference_unit'], 'single-shot invocation')
        half = stats.t.ppf(.975, 14) * data['samples'].std(ddof=1) / np.sqrt(15)
        np.testing.assert_allclose(result['mean_ci95'], [14 - half, 14 + half])
        self.assertEqual(len(data['series']), 20)
        self.assertEqual(len(data['samples']), 15)

    def test_incomplete_duplicate_and_mismatched_run_sets_are_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            path = write_http_experiment(folder, 'incomplete')
            (path / 'run-5.json').unlink()
            with self.assertRaisesRegex(ValueError, 'Incomplete'):
                a.load(path)
            path = write_http_experiment(folder, 'duplicate')
            rows = [json.loads(line) for line in (path / 'run-2.json').read_text().splitlines()]
            for row in rows:
                row['data']['tags']['run'] = '1'
            (path / 'run-2.json').write_text(''.join(json.dumps(row) + '\n' for row in rows))
            with self.assertRaisesRegex(ValueError, 'duplicate'):
                a.load(path)
            path = write_http_experiment(folder, 'mismatch')
            text = (path / 'run-3.json').read_text().replace('"vus": "1"', '"vus": "8"')
            (path / 'run-3.json').write_text(text)
            with self.assertRaisesRegex(ValueError, 'identical parameters'):
                a.load(path)

    def test_failed_run_contributes_failures_but_no_successful_latency_samples(self):
        with tempfile.TemporaryDirectory() as folder:
            path = write_http_experiment(folder, 'baseline', failed_run=3)
            data = a.load(path)
            a.select_samples(data, 1)
            result = a.summary_stats(data)
            self.assertEqual(result['replicate_count'], 5)
            self.assertEqual(result['inference_n'], 12)
            self.assertIsNotNone(result['mean_ci95'])
            self.assertAlmostEqual(result['mean'], np.repeat([10, 12, 16, 18], [1, 2, 4, 5]).mean())
            self.assertEqual(result['failures'], 3)
            self.assertIsNone(a.replicate_rows([data])[2]['mean'])
            single = a.load(path / 'run-1.json')
            a.select_samples(single, 1)
            self.assertEqual(a.summary_stats(single)['inference_n'], 1)
            self.assertIsNone(a.summary_stats(single)['mean_ci95'])
            single = a.load(path / 'run-2.json')
            a.select_samples(single, 0)
            self.assertEqual(a.summary_stats(single)['inference_n'], 3)
            self.assertIsNotNone(a.summary_stats(single)['mean_ci95'])

    def test_http_time_series_cutoffs_and_closeups_apply_to_every_run(self):
        with tempfile.TemporaryDirectory() as folder:
            data = a.load(write_http_experiment(folder, 'baseline'))
            a.select_samples(data, 1)
            figures = {}
            with patch.object(a.plt.Figure, 'savefig', lambda figure, path, **kw: figures.update({Path(path).name: figure})):
                a.plots([data], Path(folder), 25)
            sequence = figures['sequence.png'].axes[0]
            cutoffs = [line.get_xdata()[0] for line in sequence.lines if a.matplotlib.colors.to_hex(line.get_color()) == '#bf3b28']
            self.assertEqual(cutoffs, [1, 5, 9, 13, 17])
            boundaries = [line.get_xdata()[0] for line in sequence.lines if line.get_linestyle() == ':']
            self.assertEqual(boundaries, [4, 8, 12, 16])
            closeup = figures['warmup-closeup-1.png'].axes[0]
            self.assertEqual([text.get_text() for text in closeup.texts], [f'Run {i}' for i in range(1, 6)])
            offsets = np.concatenate([item.get_offsets() for item in closeup.collections])
            self.assertEqual(len(offsets), 5)
            np.testing.assert_allclose(offsets[:, 1], [1000] * 5)
