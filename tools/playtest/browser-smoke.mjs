'use strict';

// Run only through the supported cua_repl browser binding. No Playwright launch,
// account/profile access, page script injection, or network calls.
async function runLocalSmoke(tab, options = {}) {
  const seed = options.seed ?? 20261025;
  const trace = [];
  const started = Date.now();
  let rng = seed >>> 0;
  const random = n => { rng = (Math.imul(1664525, rng) + 1013904223) >>> 0; return rng % n; };
  const button = name => tab.playwright.getByRole('button', { name, exact: typeof name === 'string' });
  const assert = (ok, message) => { if (!ok) throw new Error(message); };
  async function record(action) {
    const snapshot = await tab.playwright.domSnapshot();
    trace.push({ elapsedMs: Date.now() - started, action, snapshot });
    assert(Date.now() - started < 90000, 'Browser scenario exceeded 90 seconds');
    return snapshot;
  }
  async function board() {
    const cells = await button(/^Row [1-3], column [1-3]:/).all();
    assert(cells.length === 9, 'Board must expose nine named buttons');
    const labels = await Promise.all(cells.map(c => c.getAttribute('aria-label')));
    return labels.map((label, i) => ({
      index: i, label,
      value: label.includes(': empty') ? 0 : label.includes('Ascendant') ? 1 : 2
    }));
  }
  async function play(index, keyboard = false) {
    const before = await board();
    assert(before[index].value === 0, 'Selected cell must be empty');
    const row = Math.floor(index / 3) + 1, col = index % 3 + 1;
    const target = button('Row ' + row + ', column ' + col + ': empty');
    assert(await target.isEnabled(), 'Legal cell must be enabled');
    if (keyboard) await target.press('Enter'); else await target.click();
    await button(new RegExp('^Row ' + row + ', column ' + col + ': The ')).waitFor({ state: 'visible', timeoutMs: 5000 });
    const after = await board();
    assert(after.filter(c => c.value).length === before.filter(c => c.value).length + 1,
      'A hot-seat action must place exactly one mark');
    assert(await button(new RegExp('^Row ' + row + ', column ' + col + ': The ')).isEnabled() === false,
      'Occupied cell must be disabled');
    await record({ type: keyboard ? 'keyboard-move' : 'move', index });
    return after;
  }
  function winner(cells) {
    const lines = [[0,1,2],[3,4,5],[6,7,8],[0,3,6],[1,4,7],[2,5,8],[0,4,8],[2,4,6]];
    return lines.some(line => cells[line[0]].value && line.every(i => cells[i].value === cells[line[0]].value));
  }
  try {
    await record('initial');
    await button('Hot-seat (two humans)').click();
    await button('Row 1, column 1: empty').waitFor({ state: 'visible', timeoutMs: 5000 });
    for (const [i, cell] of [0,3,1,4,2].entries()) await play(cell, i === 0);
    await button('Play again').waitFor({ state: 'visible', timeoutMs: 5000 });
    const won = await board();
    assert(won.filter(c => c.label.includes('winning cell')).length === 3, 'Winning line must contain three labelled cells');
    await record('keyboard-win');
    if (options.capture) {
      await options.capture('keyboard-win', await tab.screenshot({ fullPage: true }));
      await record('after-win-screenshot');
    }
    await button('Play again').click();
    await button('Row 1, column 1: empty').waitFor({ state: 'visible', timeoutMs: 5000 });
    assert((await board()).every(c => c.value === 0), 'Replay must clear the board');
    await button('Reset score').click();
    await tab.playwright.locator('.score-value').filter({ hasText: /^0$/ }).nth(2)
      .waitFor({ state: 'visible', timeoutMs: 5000 });
    const scores = await tab.playwright.locator('.score-value').allTextContents({});
    assert(scores.length === 3 && scores.every(value => value.trim() === '0'), 'Reset must clear all three scores');
    await record('replay-reset');

    for (let round = 0; round < 2; round++) {
      let cells = await board(), moves = 0;
      while (!winner(cells) && cells.some(c => !c.value)) {
        const legal = cells.filter(c => !c.value);
        cells = await play(legal[random(legal.length)].index);
        moves++;
        assert(moves <= 9, 'Round cannot exceed nine moves');
        const first = cells.filter(c => c.value === 1).length, second = cells.filter(c => c.value === 2).length;
        assert(first === second || first === second + 1, 'Turn counts must remain legal');
      }
      await button('Play again').waitFor({ state: 'visible', timeoutMs: 5000 });
      await record({ type: 'seeded-round-complete', round, moves });
      if (round === 0) {
        await button('Play again').click();
        await button('Row 1, column 1: empty').waitFor({ state: 'visible', timeoutMs: 5000 });
      }
    }
    if (options.capture) await options.capture('seeded-result', await tab.screenshot({ fullPage: true }));
    return { passed: true, seed, durationMs: Date.now() - started, scenarios: ['keyboard-win','replay-reset','seeded-random-round-1','seeded-random-round-2'], trace };
  } catch (error) {
    try { await record({ type: 'failure', message: error.message }); } catch {}
    if (options.capture) await options.capture('failure', await tab.screenshot({ fullPage: true }));
    error.playtest = { passed: false, seed, durationMs: Date.now() - started, trace };
    throw error;
  }
}
export { runLocalSmoke };

