#!/usr/bin/env python3
"""Statistics and plots for the supplied JMH and k6 experiments. Never runs benchmarks."""
import argparse
import csv
import html
from datetime import datetime, timezone
import itertools
import json
import math
from pathlib import Path
import sys

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import numpy as np
from scipy import stats


def numbers(values):
    result = np.asarray(values, dtype=float)
    if result.size == 0 or not np.isfinite(result).all() or (result < 0).any():
        raise ValueError('Expected nonempty, finite, nonnegative timing samples.')
    return result


def load(path):
    with path.open(encoding='utf-8') as stream:
        first = next((line for line in stream if line.strip()), '')
        stream.seek(0)
        if first.lstrip().startswith('['):
            data = json.load(stream)
            if len(data) != 1:
                raise ValueError('Export one JMH method and parameter combination per file.')
            item = data[0]
            if item['mode'] != 'ss' or item['primaryMetric']['scoreUnit'] != 'ms/op':
                raise ValueError('This lab accepts only single-shot JMH exports. Rerun with -bm ss -bs 1 -t 1 -tu ms; existing timing-mode exports cannot be converted.')
            if item.get('threads') != 1 or item.get('measurementBatchSize') != 1:
                raise ValueError('Use single-shot JMH with -t 1 -bs 1 so each raw sample times one invocation.')
            forks = [numbers(row) for row in item['primaryMetric']['rawData']]
            if len(forks) != item['forks'] or any(len(row) != item['measurementIterations'] or (row <= 0).any() for row in forks):
                raise ValueError('JMH output has missing forks/iterations or nonpositive timings.')
            observations = np.concatenate(forks)
            return dict(label=path.stem, file=str(path), kind='jmh', unit='ms/op',
                        operation=item['benchmark'], parameters=item.get('params', {}),
                        samples=observations, series=np.concatenate(forks),
                        boundaries=np.cumsum([len(row) for row in forks])[:-1],
                        all_samples=observations, forks=forks, x=None, inferential=True,
                        saved_at=datetime.fromtimestamp(path.stat().st_mtime, timezone.utc).isoformat())
        rows, finished = [], []
        for line in stream:
            if not line.strip():
                continue
            point = json.loads(line)
            if point.get('type') == 'Point' and point.get('metric') == 'experiment_elapsed_ms':
                finished.append(point['data'])
            if point.get('type') != 'Point' or point.get('metric') != 'operation_ms':
                continue
            value = point['data']
            tags = value['tags']
            if tags['phase'] in ('warmup', 'measurement'):
                if tags['success'] not in ('true', 'false'):
                    raise ValueError('Invalid success tag.')
                time = datetime.fromisoformat(value['time'].replace('Z', '+00:00')).timestamp()
                rows.append((time, value['value'], tags))
    if not rows:
        raise ValueError('No measurement points found. Use http.js and k6 --out json=FILE.')
    rows.sort(key=lambda row: row[0])
    keys = ('target', 'vus', 'seconds', 'warmup')
    keys += ('implementation', 'rule_count', 'input_id', 'operation', 'timing')
    config = {key: rows[0][2][key] for key in keys}
    if any(any(tags[key] != config[key] for key in keys) for _, _, tags in rows):
        raise ValueError('One HTTP configuration is required per file.')
    if float(config['seconds']) <= 0:
        raise ValueError('Measurement seconds must be positive.')
    if len(finished) != 1 or any(finished[0]['tags'][key] != config[key] for key in keys) or \
            finished[0]['value'] < 1000 * (float(config['seconds']) + float(config['warmup'])):
        raise ValueError('HTTP experiment is incomplete; rerun it and let measurement and drain finish.')
    all_samples = numbers([row[1] for row in rows])
    success = np.array([row[2]['success'] == 'true' for row in rows])
    starts = np.array([float(tags['elapsed_start_ms']) / 1000 for _, _, tags in rows])
    return dict(label=path.stem, file=str(path), kind='http', unit='ms',
                operation=config['operation'], parameters=config,
                samples=all_samples[success], all_samples=all_samples,
                series=all_samples, success=success, boundaries=[],
                x=starts, phases=np.array([row[2]['phase'] for row in rows]),
                inferential=False,
                saved_at=datetime.fromtimestamp(path.stat().st_mtime, timezone.utc).isoformat())


def select_samples(data, cutoff=None):
    """Keep the complete sequence; select observations for distributions and inference."""
    if cutoff is not None and (not math.isfinite(cutoff) or cutoff < 0):
        raise ValueError('Warm-up cutoff must be finite and nonnegative.')
    if data['inferential']:
        k = 0 if cutoff is None else cutoff
        if int(k) != k or any(k >= len(fork) for fork in data['forks']):
            raise ValueError('JMH cutoff must be an integer smaller than every fork’s recorded shot count.')
        data['cutoff'] = int(k)
        data['series'] = np.concatenate(data['forks'])
        data['samples'] = np.concatenate([fork[int(k):] for fork in data['forks']])
        data['all_samples'] = data['samples']
    else:
        total = float(data['parameters']['warmup']) + float(data['parameters']['seconds'])
        data['cutoff'] = 0. if cutoff is None else cutoff
        if data['cutoff'] >= total:
            raise ValueError('HTTP cutoff must be smaller than warm-up plus measurement duration.')
        keep = data['x'] >= data['cutoff']
        if not keep.any():
            raise ValueError('No requests remain after this cutoff.')
        data['all_samples'] = data['series'][keep]
        data['samples'] = data['series'][keep & data['success']]
        data['measurement_seconds'] = total - data['cutoff']


def validate_comparisons(datasets):
    if len({(d['kind'], d['operation'], d['unit'], d['parameters'].get('timing'),
             d['parameters'].get('inputId', d['parameters'].get('input_id'))) for d in datasets}) != 1:
        raise ValueError('Compare files from the same scope/operation, workload/input and timing method; analyze different boundaries or workload versions separately.')
    validate_implementation_comparison(datasets)


def validate_implementation_comparison(datasets):
    implementations = {d['parameters'].get('implementation') for d in datasets}
    if len(implementations) < 2:
        return
    settings = set()
    for d in datasets:
        p = d['parameters']
        settings.add((p.get('ruleCount', p.get('rule_count')),
                      p.get('inputId', p.get('input_id')), p.get('vus')))
    if len(settings) != 1:
        raise ValueError('For an implementation comparison, keep rule count, input and HTTP client count identical.')


def summary_stats(data):
    x = data['samples']
    n = len(x)
    mean = float(x.mean()) if n else None
    sd = float(x.std(ddof=1)) if n > 1 else None
    ci = None
    if data['inferential'] and n > 1:
        half = float(stats.t.ppf(.975, n - 1)) * sd / math.sqrt(n)
        ci = [mean - half, mean + half]
    result = dict(experiment=data['label'], file=data['file'], kind=data['kind'],
                  operation=data['operation'], parameters=data['parameters'], unit=data['unit'],
                  saved_at=data.get('saved_at'), n=n, sample_unit='single-shot invocation' if data['inferential'] else 'successful request',
                  mean=mean, median=float(np.median(x)) if n else None,
                  p90=float(np.percentile(x, 90)) if n else None,
                  p95=float(np.percentile(x, 95)) if n else None,
                  min=float(x.min()) if n else None, max=float(x.max()) if n else None,
                  range=float(np.ptp(x)) if n else None,
                  variance=float(x.var(ddof=1)) if n > 1 else None,
                  sd=sd, cv=sd / mean if sd is not None and mean else None,
                  sem=sd / math.sqrt(n) if data['inferential'] and n > 1 else None,
                  mean_ci95=ci, ci95_width=ci[1] - ci[0] if ci else None,
                  max_distance_from_mean=float(np.abs(x - mean).max()) if n else None,
                  warmup_cutoff=data['cutoff'])
    if data['kind'] == 'jmh':
        result.update(jmh_mode='ss', threads=1, measurement_batch_size=1, fork_count=len(data['forks']),
                      inference_assumption='Pooled single-shot samples treated as independent; within-fork dependence can overstate precision.')
    if data['kind'] == 'http':
        count = len(data['all_samples'])
        result.update(attempts=count, failures=count - n, failure_fraction=(count - n) / count,
                      all_attempt_mean_ms=float(data['all_samples'].mean()),
                      successful_ops_per_s=n / data['measurement_seconds'])
    return result


def comparisons(datasets):
    result = []
    for a, b in itertools.combinations(datasets, 2):
        x, y = a['samples'], b['samples']
        ma, mb = (float(z.mean()) if len(z) else None for z in (x, y))
        row = dict(a=a['label'], b=b['label'], unit=a['unit'],
                   mean_b_minus_a=mb - ma if ma is not None and mb is not None else None,
                   time_ratio_a_over_b=ma / mb if ma is not None and mb else None,
                   time_reduction_percent=100 * (ma - mb) / ma if ma and mb is not None else None,
                   welch_p=None, mann_whitney_p=None)
        if a['inferential'] and min(len(x), len(y)) > 1:
            if x.var() > 0 or y.var() > 0:
                p = float(stats.ttest_ind(x, y, equal_var=False).pvalue)
                row['welch_p'] = p if math.isfinite(p) else None
            row['mann_whitney_p'] = float(stats.mannwhitneyu(x, y, alternative='two-sided').pvalue)
        if a['kind'] == 'http':
            sa, sb = summary_stats(a), summary_stats(b)
            row.update(failure_percentage_points_b_minus_a=100 * (sb['failure_fraction'] - sa['failure_fraction']),
                       successful_ops_per_s_a=sa['successful_ops_per_s'],
                       successful_ops_per_s_b=sb['successful_ops_per_s'])
        result.append(row)
    return result


def save_csv(path, rows):
    with path.open('w', newline='', encoding='utf-8') as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader()
        for row in rows:
            writer.writerow({k: json.dumps(v) if isinstance(v, (dict, list)) else v for k, v in row.items()})


def conclusions(summaries, pairs):
    def fmt(value):
        return 'unavailable' if value is None else f'{value:.5g}'

    def lower(a, b, key):
        if a[key] is None or b[key] is None:
            return 'No successful observations in at least one experiment.'
        if a[key] == b[key]:
            return 'The observed values are equal.'
        return f"{'A' if a[key] < b[key] else 'B'} has the lower observed value."

    groups = []
    for pair in pairs:
        a, b = [next(s for s in summaries if s['experiment'] == pair[key]) for key in ('a', 'b')]
        unit = a['unit']
        rows = []
        def add(method, result, interpretation):
            rows.append(dict(method=method, result=result, interpretation=interpretation))
        for method, key in [('Means', 'mean'), ('Medians', 'median'), ('90th percentile', 'p90')]:
            add(method, f"A: {fmt(a[key])}; B: {fmt(b[key])} {unit}", lower(a, b, key))
        ratio = pair['time_ratio_a_over_b']
        add('Speedup (mean ratio A/B)', fmt(ratio),
            'Unavailable when B has zero mean or either sample is empty.' if ratio is None else 'A has zero mean; percentage time change is undefined.' if pair['time_reduction_percent'] is None else
            f"B takes {abs(pair['time_reduction_percent']):.3g}% {'less' if ratio >= 1 else 'more'} time than A. Workloads may differ.")
        ci_a, ci_b = a['mean_ci95'], b['mean_ci95']
        overlap = ci_a is not None and ci_b is not None and max(ci_a[0], ci_b[0]) <= min(ci_a[1], ci_b[1])
        add('95% confidence intervals',
            f"A: [{fmt(ci_a[0])}, {fmt(ci_a[1])}]; B: [{fmt(ci_b[0])}, {fmt(ci_b[1])}] {unit}" if ci_a and ci_b else 'Not estimated',
            ('Intervals overlap.' if overlap else 'Intervals do not overlap.') + ' Pooled-iteration intervals assume independence. Overlap alone is not a test of the difference.'
            if ci_a and ci_b else 'Requires independent samples; dependent HTTP requests are descriptive only.')
        for method, key, assumption in [('Welch two-sample t-test', 'welch_p', 'Tests a difference in means assuming independent single-shot samples.'),
                                         ('Mann–Whitney U test', 'mann_whitney_p', 'Assumes independent single-shot samples; tests distributions/ranks, not universally medians.')]:
            p = pair[key]
            add(method, f'p = {fmt(p)}' if p is not None else 'Not estimated',
                (('Evidence of a difference at α = 0.05. ' if p < .05 else 'Insufficient evidence at α = 0.05; this does not establish equality. ') + assumption)
                if p is not None else 'Requires independent observations and enough data; no test for dependent HTTP requests.')
        if a['kind'] == 'http':
            add('Failure fraction', f"A: {100*a['failure_fraction']:.3g}%; B: {100*b['failure_fraction']:.3g}%", 'Read successful-response latency together with failures.')
            add('Successful operations/s', f"A: {fmt(a['successful_ops_per_s'])}; B: {fmt(b['successful_ops_per_s'])}", 'Throughput uses retained request starts divided by the selected time window, including drain completions.')
        groups.append(dict(a=a['experiment'], b=b['experiment'], rows=rows))
    if not pairs:
        a = summaries[0]
        groups.append(dict(a=a['experiment'], b=None, rows=[
            dict(method=label, result=f"{fmt(a[key])} {a['unit']}", interpretation=meaning)
            for label, key, meaning in [('Mean', 'mean', 'Average retained observation.'), ('Median', 'median', 'Middle of the retained observations.'),
                                       ('90th percentile', 'p90', '90% of retained observations are at or below this value.'),
                                       ('Standard deviation', 'sd', 'Spread of retained observations; inspect the sequence for trends.')]]))
        ci = a['mean_ci95']
        groups[-1]['rows'].append(dict(method='95% confidence interval for the mean',
            result=f'[{fmt(ci[0])}, {fmt(ci[1])}] {a["unit"]}' if ci else 'Not estimated',
            interpretation='Student t interval across pooled single-shot samples, assuming independence; within-fork dependence can overstate precision.' if ci else 'No interval for dependent HTTP requests or insufficient independent observations.'))
    return groups


def closeup_percentage(value):
    value = float(value)
    if not math.isfinite(value) or not 0 < value <= 100:
        raise ValueError('Close-up percentage must be greater than 0 and at most 100.')
    return value


def plots(datasets, out, closeup_percent=10):
    closeup_percent = closeup_percentage(closeup_percent)
    colors = [plt.get_cmap('viridis')(v) for v in np.linspace(.2, .8, len(datasets))]
    nonempty = [d['samples'] for d in datasets if len(d['samples'])]
    bins = np.histogram_bin_edges(np.concatenate(nonempty), bins=min(30, max(1, int(math.sqrt(max(map(len, nonempty))))))) if nonempty else [0, 1]
    gallery = []
    groups = [([i], d['label'], str(i+1)) for i, d in enumerate(datasets)]
    if len(datasets) > 1:
        groups.append((list(range(len(datasets))), 'Joint comparison', 'joint'))
    for indices, title, suffix in groups:
        fig, axes = plt.subplots(1, 2, figsize=(11, 4.5))
        for i in indices:
            d, color = datasets[i], colors[i]
            x = d['samples']
            if not len(x):
                axes[0].plot([], [], color=color, label=d['label'] + ': no successes')
                continue
            axes[0].hist(x, bins=np.histogram_bin_edges(x, bins=min(30, max(1, int(math.sqrt(len(x)))))) if len(indices) == 1 else bins, density=True, alpha=.45, color=color, label=d['label'])
            mean = x.mean()
            axes[0].axvline(mean, color=color, linestyle='--', label=d['label'] + ' mean')
            if len(x) > 1:
                sd = x.std(ddof=1)
                axes[0].axvspan(mean-sd, mean+sd, color=color, alpha=.08, label=d['label'] + ' mean ± SD')
                ci = summary_stats(d)['mean_ci95']
                if ci:
                    for j, edge in enumerate(ci):
                        axes[0].axvline(edge, color=color, linestyle=':', label=d['label'] + ' 95% mean CI' if j == 0 else None)
            x = np.sort(x)
            axes[1].step(np.r_[x[0], x], np.r_[0, np.arange(1, len(x)+1)/len(x)], where='post', color=color, label=d['label'])
        for ax in axes:
            ax.set_xlabel(datasets[0]['unit'])
            if ax.get_legend_handles_labels()[0]:
                ax.legend(fontsize=8)
        axes[0].set_ylabel('Density')
        axes[1].set(ylabel='Cumulative fraction', ylim=(0, 1.02))
        fig.suptitle(title + (' — retained single-shot samples' if datasets[0]['inferential'] else ' — retained successful requests'))
        fig.tight_layout()
        filename = 'distributions.png' if suffix == 'joint' or len(datasets) == 1 else f'distribution-{suffix}.png'
        fig.savefig(out / filename, dpi=140)
        plt.close(fig)
        gallery.append(dict(kind='distribution', title=title, file=filename))
    for indices, title, suffix in groups:
        fig, ax = plt.subplots(figsize=(11, 4))
        marked_boundaries, marked_cutoffs = set(), set()
        for i in indices:
            d, color = datasets[i], colors[i]
            if d['inferential']:
                # Preserve JMH rawData order: every iteration of fork 1, then fork 2, etc.
                offset = 0
                base_color = np.asarray(color[:3])
                for fork_index, fork in enumerate(d['forks']):
                    shade = base_color * .85 if fork_index % 2 == 0 else base_color * .75 + .25
                    ax.plot(np.arange(offset+1, offset+len(fork)+1), fork, color=shade, alpha=.9,
                            label=d['label'] if fork_index == 0 else None)
                    offset += len(fork)
                boundaries = np.cumsum([len(fork) for fork in d['forks']])[:-1]
                for boundary in boundaries:
                    if boundary not in marked_boundaries:
                        ax.axvline(boundary+.5, color='grey', linestyle=':', label='JVM fork boundary' if not marked_boundaries else None)
                        marked_boundaries.add(boundary)
                for start in np.r_[0, boundaries]:
                    # With zero excluded samples, only mark the start of the full series.
                    if not d['cutoff'] and start:
                        continue
                    key = (int(start), d['cutoff'])
                    if key not in marked_cutoffs:
                        ax.axvline(start+d['cutoff']+.5, color='#bf3b28', linestyle='--', label=f"Excluded samples per fork: {d['cutoff']:g}" if not marked_cutoffs else None)
                        if d['cutoff']:
                            ax.axvspan(start+.5, start+d['cutoff']+.5, color='#bf3b28', alpha=.07)
                        marked_cutoffs.add(key)
                ax.set_xlabel('Measurement sample in execution order (forks concatenated)')
            else:
                for ok, marker, label in [(True, '.', 'success'), (False, 'x', 'failure')]:
                    selected = d['success'] == ok
                    if selected.any():
                        ax.scatter(d['x'][selected], d['series'][selected], s=9, marker=marker, color=color, label=d['label'] + ' ' + label, alpha=.65)
                cutoff = d['cutoff']
                ax.set_xlabel('Request start seconds since scenario start')
                if cutoff not in marked_cutoffs:
                    ax.axvline(cutoff, color='#bf3b28', linestyle='--', label=f"Excluded prefix ends: {cutoff:g}")
                    ax.axvspan(0, cutoff, color='#bf3b28', alpha=.07)
                    marked_cutoffs.add(cutoff)
        ax.set(title=title + ' — full sequence (including excluded prefix)', ylabel=datasets[0]['unit'])
        ax.legend(fontsize=8)
        fig.tight_layout()
        filename = 'sequence.png' if suffix == 'joint' or len(datasets) == 1 else f'sequence-{suffix}.png'
        fig.savefig(out / filename, dpi=140)
        plt.close(fig)
        gallery.append(dict(kind='sequence', title=title, file=filename))
    # Inspect original early observations, regardless of the cutoff used for statistics.
    for i, d in enumerate(datasets):
        fig, ax = plt.subplots(figsize=(11, 4))
        if d['kind'] == 'jmh':
            offset = 0
            base_color = np.asarray(colors[i][:3])
            for fork_index, fork in enumerate(d['forks']):
                count = max(1, math.ceil(len(fork) * closeup_percent / 100))
                prefix = fork[:count]
                shade = base_color * .85 if fork_index % 2 == 0 else base_color * .75 + .25
                ax.plot(np.arange(offset+1, offset+count+1), prefix, color=shade, alpha=.9,
                        marker='.' if count == 1 else None)
                if fork_index:
                    ax.axvline(offset+.5, color='grey', linestyle=':')
                ax.text(offset+(count+1)/2, .97, f'Fork {fork_index+1}', transform=ax.get_xaxis_transform(),
                        ha='center', va='top', fontsize=8)
                offset += count
            scope = 'each fork'
            ax.set_xlabel(f'Sample within concatenated fork prefixes (first {closeup_percent:g}% each)')
        else:
            duration = float(d['parameters']['warmup']) + float(d['parameters']['seconds'])
            window = duration * closeup_percent / 100
            prefix = np.ones(len(d['x']), dtype=bool) if closeup_percent == 100 else d['x'] < window
            for ok, marker, label in [(True, '.', 'success'), (False, 'x', 'failure')]:
                selected = prefix & (d['success'] == ok)
                if selected.any():
                    ax.scatter(d['x'][selected], d['series'][selected], s=12, marker=marker,
                               color=colors[i], label=label, alpha=.7)
            if ax.has_data():
                ax.legend(fontsize=8)
            else:
                ax.text(.5, .5, 'No requests started in this window.', transform=ax.transAxes, ha='center')
            ax.set(xlabel='Request start seconds since scenario start', xlim=(0, window))
            scope = 'the run duration'
        ax.set(title=d['label'] + f' — warm-up close-up (first {closeup_percent:g}% of {scope})', ylabel=d['unit'])
        fig.tight_layout()
        filename = f'warmup-closeup-{i+1}.png'
        fig.savefig(out / filename, dpi=140)
        plt.close(fig)
        gallery.append(dict(kind='warmup', title=d['label'], file=filename))
    return gallery


def save_page(out, summaries, pairs, notes, conclusion_groups, gallery, generated_at=None, closeup_percent=10):
    generated_at = generated_at or datetime.now(timezone.utc).isoformat()
    def table(rows):
        if not rows:
            return ''
        def cell(value):
            if value is None:
                return '—'
            if isinstance(value, float):
                return f'{value:.6g}'
            if isinstance(value, list) and all(isinstance(v, (int, float)) for v in value):
                return '[' + ', '.join(f'{v:.6g}' for v in value) + ']'
            if isinstance(value, (dict, list)):
                value = json.dumps(value)
            return html.escape(str(value))
        headers = ''.join(f'<th>{html.escape(key.replace("_", " "))}</th>' for key in rows[0])
        body = ''.join('<tr>' + ''.join(f'<td>{cell(value)}</td>' for value in row.values()) + '</tr>' for row in rows)
        return f'<div class="scroll"><table><thead><tr>{headers}</tr></thead><tbody>{body}</tbody></table></div>'
    display = [{key: row.get(key) for key in ('experiment', 'saved_at', 'unit', 'n', 'sample_unit', 'fork_count', 'parameters', 'mean', 'median', 'p90', 'p95', 'sd', 'cv', 'mean_ci95',
                                              'failures', 'failure_fraction', 'all_attempt_mean_ms', 'successful_ops_per_s')
                if key in row} for row in summaries]
    title = 'Comparison' if pairs else 'Experiment'
    conclusion_html = ''.join('<h3>' + html.escape(f"A: {g['a']}" + (f" · B: {g['b']}" if g['b'] else '')) + '</h3>' + table(g['rows']) for g in conclusion_groups)
    jmh = summaries[0]['kind'] == 'jmh'
    def caption(item):
        if item['kind'] == 'warmup':
            detail = (f'This time series shows only the first {closeup_percent:g}% of recorded shots in each JVM fork, rounded up to at least one shot. '
                      'The fork prefixes are concatenated in execution order; omitted later samples leave no gaps on the horizontal axis. '
                      'Fork labels, alternating shades and grey dotted boundaries identify the prefixes. ' if jmh else
                      f'This time series shows requests started in the first {closeup_percent:g}% of the configured run duration (warm-up plus measurement). '
                      'The horizontal axis is seconds since scenario start. Dots are successful responses; crosses are failures. ')
            return (detail + 'Vertical values are invocation times in milliseconds. Use this view to inspect early settling and spikes. '
                    'It always includes the original early samples, regardless of the selected cutoff. '
                    'The percentage is a viewing window, not an estimated warm-up duration, and does not change statistics or exclude samples.')
        if item['kind'] == 'distribution':
            sample = 'all retained single-shot invocation timings from every JVM fork' if jmh else 'successful-request latencies after the selected time cutoff'
            return ('These are distributions of ' + sample + '. Left: a histogram; the horizontal axis is time per operation, '
                    'and the vertical axis is probability density, not a count or speedup ratio. Bar area (height × width) gives the fraction of observations in that interval; total area is 1. '
                    'Right: the empirical cumulative distribution (ECDF). Its vertical axis is the fraction at or below the horizontal-axis value: 0.9 means 90%. '
                    'Dashed vertical lines show means, shaded bands show mean ± one standard deviation; dotted lines, when present, show 95% confidence intervals for the pooled mean under an independent-sample assumption. '
                    + ('Colors distinguish the experiments; the joint histogram uses common bins to compare their shapes. ' if item['title'] == 'Joint comparison' else '')
                    + 'No speedup ratio is plotted. In the conclusion table A/B divides A’s mean time by B’s: 2 means B takes half as much time (50% less).')
        return (('This is a measurement sequence. The horizontal axis is sample number in execution order: all recorded shots of fork 1, followed by fork 2, and so on. Successive forks alternate darker and lighter shades of the experiment’s color; grey dotted lines mark their boundaries. Each point times one benchmark-method invocation, recorded with single-shot mode, batch size 1 and one thread. The axis gives sample order, not elapsed wall-clock seconds. ' if jmh else
                 'This is a time series. The horizontal axis is request-start time in seconds since scenario start, and the vertical axis is request latency in milliseconds. Dots are successful responses; crosses are failed responses. ')
                + 'Colors identify experiments. Red dashed lines mark the selected cutoff; shaded prefixes stay visible here but are excluded from distributions and statistics. '
                + ('A cutoff of N excludes the first N recorded shots per fork. Actual JMH warm-up iterations are not exported. ' if jmh else '')
                + 'Look for early settling, drift, spikes or changes in variability. The vertical values are timings, not ratios.')
    section_titles = {'distribution': 'Distributions', 'sequence': 'Time series', 'warmup': 'Warm-up close-up'}
    gallery_html = ''.join('<h2>' + section_titles[kind] + '</h2>' + ''.join(
        '<h3>' + html.escape(item['title']) + '</h3><figure><img src="' + item['file'] + '" alt="' + html.escape(item['title'] + ' ' + kind, quote=True) + '"><figcaption>' + html.escape(caption(item)) + '</figcaption></figure>'
        for item in gallery if item['kind'] == kind) for kind in ('distribution', 'sequence', 'warmup') if any(item['kind'] == kind for item in gallery))
    cutoff = summaries[0]['warmup_cutoff']
    control_note = ('JMH JSON omits actual warm-up timings. This excludes the first N recorded single-shot samples in every fork. All other samples contribute directly to statistics and distributions. The time axis concatenates forks in execution order.' if jmh else
                    'Seconds since scenario start. Earlier request starts are excluded from statistics and distributions; the full sequence remains visible. One cutoff applies to all compared runs.')
    page = f'''<!doctype html><html lang="en"><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>{title} results</title>
<style>body{{font:16px system-ui;margin:30px;color:#18202b}}a{{color:#1758aa}}.scroll{{overflow-x:auto}}
table{{border-collapse:collapse;font-size:14px}}th,td{{padding:9px;border:1px solid #ddd;text-align:left;vertical-align:top}}
th{{background:#f1f4f8}}td{{min-width:130px;max-width:520px}}button,input{{font:inherit;padding:7px}}figure{{margin:16px 0 32px}}figcaption{{max-width:1100px;line-height:1.5;color:#435063}}img{{max-width:100%;height:auto}}li{{margin:8px 0}}</style>
<p><a href="/">All analyses</a></p><h1>{title} results</h1><p>Analysis generated: <time>{html.escape(generated_at)}</time> (UTC).</p>
<h2>Warm-up selection</h2><p>{control_note}</p>
<form method="post" action="/reanalyze" id="warmup-form">
<label for="cutoff">{'Single shots to exclude per fork' if jmh else 'End of warm-up (seconds)'}</label>
<input id="cutoff" name="cutoff" type="number" min="0" step="{'1' if jmh else '0.1'}" value="{cutoff:g}" required>
<label for="closeup-percent">Close-up window (%)</label>
<input id="closeup-percent" name="closeup_percent" type="number" min="0" max="100" step="any" value="{closeup_percent:g}" required oninput="this.setCustomValidity(this.valueAsNumber > 0 ? '' : 'Choose a percentage greater than 0.');">
<p>The close-up percentage changes only the viewing window; the cutoff controls which samples enter statistics.</p>
<input id="report" name="report" type="hidden"><button type="submit">Update analysis</button>
<span id="progress" role="status"></span></form>
<script>let reportPath = decodeURIComponent(location.pathname);
if (reportPath.endsWith('/index.html')) reportPath = reportPath.slice(0, -11);
if (reportPath.endsWith('/')) reportPath = reportPath.slice(0, -1);
document.getElementById('report').value = reportPath;
document.getElementById('warmup-form').addEventListener('submit', () => {{document.getElementById('progress').textContent = 'Updating plots and statistics…';}});</script>
<h2>Summary of conclusions</h2>{conclusion_html}
<h2>Statistics</h2>{table(display)}
{'<h2>Pairwise comparisons</h2>' + table(pairs) if pairs else ''}
{gallery_html}
<h2>Interpretation</h2><ul>{''.join('<li>' + html.escape(note) + '</li>' for note in notes)}</ul>
<p><a href="conclusions.csv" download>Conclusions CSV</a> · <a href="stats.csv" download>Statistics CSV</a> · <a href="stats.json" download>Full statistics JSON</a>
{' · <a href="comparisons.csv" download>Comparisons CSV</a>' if pairs else ''}</p></html>'''
    # Write the index last, so the viewer lists only fully generated reports.
    temporary = out / '.index.html.tmp'
    temporary.write_text(page, encoding='utf-8')
    temporary.replace(out / 'index.html')


def main():
    parser = argparse.ArgumentParser(description='Analyze one experiment or compare several compatible result files.')
    parser.add_argument('files', type=Path, nargs='+')
    parser.add_argument('--warmup-cutoff', type=float, default=0, help='Exclude request starts before this second, or this many recorded single shots per JMH fork.')
    parser.add_argument('--closeup-percent', type=float, default=10, help='First percentage of shots per JMH fork or HTTP run duration to show (0 < value <= 100; default 10).')
    parser.add_argument('-o', '--out', type=Path, default=Path('analysis'))
    args = parser.parse_args()
    try:
        args.closeup_percent = closeup_percentage(args.closeup_percent)
        if len({p.resolve() for p in args.files}) != len(args.files):
            raise ValueError('Supply distinct experiment files.')
        data = [load(path) for path in args.files]
        names = [d['label'] for d in data]
        for item in data:
            if names.count(item['label']) > 1:
                item['label'] = item['file']
        validate_comparisons(data)
        for item in data:
            select_samples(item, args.warmup_cutoff)
        summaries = [summary_stats(d) for d in data]
        pairs = comparisons(data)
        notes = ['Compare implementation ratios at fixed input, rule count and client count. Changing rule count or client count changes the workload.',
                 'Small samples give uncertain estimates. A large p-value does not prove equality; Mann–Whitney is not a universal median test.']
        notes += (['JMH uses single-shot mode (-bm ss), batch size 1 (-bs 1) and one thread (-t 1). Each raw sample times one benchmark-method invocation. Statistics and distributions pool every retained shot across forks.',
                   'JMH confidence intervals, SEM and p-values treat pooled single-shot samples as independent for illustration. Within-fork dependence can overstate precision; interpret these inferential results with that assumption. Sequence plots concatenate forks in recorded order and mark their boundaries. The cutoff removes a measured prefix per fork; actual JMH warm-up timings are absent from the JSON.']
                  if data[0]['inferential'] else
                  ['HTTP statistics are descriptive; requests within one run are dependent, so no CI, SEM or hypothesis tests are reported.',
                   'Latency summaries use successful responses; inspect failures and all-attempt mean too.',
                   'Successful operations/s uses retained request starts divided by total configured duration minus the selected cutoff; drain completions are included.'])
        if data[0]['parameters'].get('timing') == 'http-duration-ms':
            notes.append('HTTP latency uses the k6 request duration in fractional milliseconds (sending + waiting + receiving), excluding connection setup and client-side response validation.')
        conclusion_groups = conclusions(summaries, pairs)
        generated_at = datetime.now(timezone.utc).isoformat()
        args.out.mkdir(parents=True, exist_ok=True)
        (args.out / 'stats.json').write_text(json.dumps(dict(generated_at=generated_at, closeup_percent=args.closeup_percent, experiments=summaries, comparisons=pairs, conclusions=conclusion_groups, notes=notes),
                                                     indent=2, allow_nan=False) + '\n', encoding='utf-8')
        save_csv(args.out / 'stats.csv', summaries)
        if pairs:
            save_csv(args.out / 'comparisons.csv', pairs)
        elif (args.out / 'comparisons.csv').exists():
            (args.out / 'comparisons.csv').unlink()
        save_csv(args.out / 'conclusions.csv', [dict(a=g['a'], b=g['b'], **row) for g in conclusion_groups for row in g['rows']])
        gallery = plots(data, args.out, args.closeup_percent)
        save_page(args.out, summaries, pairs, notes, conclusion_groups, gallery, generated_at, args.closeup_percent)
        print(json.dumps(dict(generated_at=generated_at, closeup_percent=args.closeup_percent, experiments=summaries, comparisons=pairs, conclusions=conclusion_groups, notes=notes), indent=2, allow_nan=False))
        print(f'Saved statistics and plots in {args.out.resolve()}')
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(2, f'Error: {error}\n')


if __name__ == '__main__':
    main()
