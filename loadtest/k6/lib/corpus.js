/**
 * The prompt corpus.
 *
 * The point of this file is that the CACHE HIT RATIO IS AN INPUT, not an
 * accident. A load test firing random strings measures the upstream path and
 * nothing else; one firing a single repeated string measures Redis. Neither
 * tells you how the gateway behaves, because the thing that determines its
 * latency is which tier answers - and that is decided entirely by the repeat
 * structure of the traffic.
 *
 * So the mix is declared and the resulting hit ratio is predictable:
 *
 *   EXACT      a prompt seen before, byte for byte      -> Tier 1
 *   PARAPHRASE a reworded form of a seen prompt         -> Tier 2
 *   UNIQUE     never seen                               -> upstream
 *
 * The USL fit is only comparable across steps if this mix is identical at every
 * step, which is why it lives in one place and is asserted in the summary.
 */

/**
 * Base questions. Deliberately short: the gateway skips Tier 2 above 1,000
 * characters, because MiniLM truncates at 256 tokens and two different long
 * prompts sharing an opening paragraph produce identical vectors. A corpus of
 * long prompts would measure the Tier-2 skip path while appearing to measure
 * Tier 2.
 */
const BASE = [
  'How do I reset my password?',
  'What are your business hours?',
  'How do I cancel my subscription?',
  'Where can I find my invoice?',
  'How do I add a team member?',
  'What payment methods do you accept?',
  'How do I export my data?',
  'Why was my card declined?',
  'How do I enable two-factor authentication?',
  'What is your refund policy?',
  'How do I change my email address?',
  'Can I transfer my account to someone else?',
];

/**
 * Rewordings of the same questions, one per base entry.
 *
 * These are the Tier-2 test: close enough in meaning to clear a 0.95 cosine
 * threshold, different enough in wording that the SHA-256 of the normalised
 * prompt differs and Tier 1 cannot answer. If a rewording is too distant the
 * gateway correctly refuses it and the step silently measures the upstream path
 * instead - which is why the hit ratio is checked in the summary rather than
 * assumed.
 */
const PARAPHRASE = [
  'password reset steps?',
  'when are you open?',
  'steps to cancel my subscription',
  'where do I get my invoice',
  'adding a team member - how?',
  'which payment methods work?',
  'how can I export data',
  'my card got declined, why?',
  'turning on 2FA - how do I do it',
  'what is the refund policy',
  'changing my email - how',
  'transferring an account to another person',
];

/** A monotonically increasing suffix, so UNIQUE prompts really are unique. */
let uniqueCounter = 0;

/**
 * Picks a prompt according to the configured mix.
 *
 * @param {number} exactPercent fraction of requests that should be exact repeats
 * @param {number} paraphrasePercent fraction that should be paraphrases
 * @returns {{prompt: string, expected: 'tier1'|'tier2'|'upstream'}}
 */
export function nextPrompt(exactPercent, paraphrasePercent) {
  const roll = Math.random();

  if (roll < exactPercent) {
    const index = Math.floor(Math.random() * BASE.length);
    return { prompt: BASE[index], expected: 'tier1' };
  }

  if (roll < exactPercent + paraphrasePercent) {
    const index = Math.floor(Math.random() * PARAPHRASE.length);
    return { prompt: PARAPHRASE[index], expected: 'tier2' };
  }

  uniqueCounter += 1;
  // The counter is per VU, and k6 runs VUs in separate JS contexts, so the
  // __VU id has to be part of the string or two VUs generate the same
  // "unique" prompts and quietly create Tier-1 hits.
  const vu = typeof __VU === 'number' ? __VU : 0;
  return {
    prompt: `Explain requirement ${vu}-${uniqueCounter} of the onboarding process.`,
    expected: 'upstream',
  };
}

/**
 * Every base prompt, for pre-warming.
 *
 * A sweep MUST pre-warm, or step 1 runs against a cold cache and every later
 * step against a warm one - so the hit ratio climbs across the sweep and the
 * fit describes a cache filling up rather than a system scaling.
 */
export function warmupPrompts() {
  return BASE.slice();
}

export const CORPUS_SIZE = BASE.length;
