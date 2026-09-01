/**
 *  Telemetry Webhook Template, Google Apps Script
 *
 *  Generalized from Automation Map's own Apps Script webhook (removed as a
 *  shipped feature in Automation Map v2.1.8; kept here as a reusable
 *  pattern). See this directory's README for the threat/privacy boundaries
 *  this design assumes - read that before reusing this, not just this file.
 *
 *  Receives one row per report from every installation of the app that owns
 *  this endpoint. No token - the endpoint is open ingestion, protected only
 *  by the payload-shape check below, not by a secret. A secret shipped in
 *  public driver source authenticates no one; worst case of abuse here is
 *  junk rows in the sheet.
 *
 *  @version 1.0.0
 *  @author  Your Name
 *  @see     https://github.com/your-org/your-repo
 *
 *  Copyright 2026 Gordon Thelander
 *  Licensed under the Apache License, Version 2.0. You may obtain a copy at:
 *      http://www.apache.org/licenses/LICENSE-2.0
 *  Distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 *
 *  Deployment checklist:
 *  1. Create a Google Sheet and copy its ID from the URL between /d/ and /edit.
 *  2. Replace REPLACE_WITH_YOUR_SPREADSHEET_ID below.
 *  3. Replace the entire Apps Script editor contents with this complete file and save.
 *  4. Confirm the function picker lists doGet, doPost and setupTelemetrySheet.
 *     If it still lists only myFunction, the complete file was not saved.
 *  5. Run setupTelemetrySheet once and approve the requested spreadsheet access.
 *  6. Deploy as a Web App: execute as yourself, access set to Anyone.
 *  7. Open the /exec URL. Do not continue until it returns JSON containing
 *     "ok":true, "configured":true, and a "scriptVersion" matching
 *     SCRIPT_VERSION below. A mismatched scriptVersion means the deployment is
 *     serving older code: saving the editor does NOT update a live deployment.
 *     Use Deploy -> Manage deployments -> edit -> Version: New version.
 *  8. Put that verified /exec URL into TELEMETRY_URL in the driver template.
 *
 *  Note on the header row: setupTelemetrySheet only writes HEADERS into an
 *  EMPTY sheet. Adding a column to HEADERS later does not relabel an existing
 *  sheet, so add the new header cell by hand. Data still lands in the new
 *  column either way; only the label is missing.
 */

// Bumped whenever this file changes. doGet returns it, so a single GET on the
// /exec URL proves which version is actually DEPLOYED - editing and saving the
// editor does not update a live deployment, and without this marker a stale
// deployment is indistinguishable from a current one.
const SCRIPT_VERSION = '1.0.0';

const SHEET_ID = 'REPLACE_WITH_YOUR_SPREADSHEET_ID';
const SHEET_NAME = 'Telemetry';
const MAX_STRING_LENGTH = 40;
// Replace with the timezone you want "received at" timestamps recorded in.
const RECEIVED_TIME_ZONE = 'UTC';
// Replace with your own payload's field list, in the exact order you want
// columns to appear. Keep this list small, fixed, and reviewed - never add
// a field that could carry free text or identifying information.
const HEADERS = [
  'receivedAt', 'scanTimestamp', 'appVersion'
];

// Example category-code allowlist for an "errors" field, if your payload has
// one. Fixed allowlist, not free text - a code outside this set is rejected
// rather than silently stored, so "no free text" stays actually true rather
// than merely documented.
const KNOWN_ERROR_CODES = [];

function doGet(e) {
  return json_({
    ok: true,
    service: 'Telemetry Webhook Template',
    method: 'GET',
    scriptVersion: SCRIPT_VERSION,
    configured: isConfigured_(),
    columns: HEADERS.length,
    time: new Date().toISOString()
  });
}

function doPost(e) {
  try {
    const payload = parseJson_(e);
    const row = validatedRow_(payload);

    if (!isConfigured_()) throw new Error('SHEET_ID has not been configured');

    const lock = LockService.getScriptLock();
    lock.waitLock(10000);
    try {
      const sheet = getTelemetrySheet_();
      appendTelemetryRow_(sheet, row);
    } finally {
      lock.releaseLock();
    }

    return json_({ ok: true });

  } catch (err) {
    return json_({
      ok: false,
      error: String(err && err.message ? err.message : err)
    });
  }
}

function parseJson_(e) {
  if (!e || !e.postData || !e.postData.contents) {
    throw new Error('Missing POST body');
  }
  return JSON.parse(e.postData.contents);
}

// Strict shape check - reject anything that does not look like a real
// report rather than trying to coerce or partially accept it. Customize the
// fields checked here to match your own HEADERS/payload shape.
function validatedRow_(payload) {
  if (!payload || typeof payload !== 'object' || Array.isArray(payload)) {
    throw new Error('POST body must be a JSON object');
  }
  const appVersion = sanitiseString_(payload.appVersion);
  const timestamp = sanitiseString_(payload.timestamp);

  if (!appVersion) {
    throw new Error('Missing appVersion');
  }
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/.test(timestamp)) {
    throw new Error('Invalid timestamp');
  }

  // Order must match HEADERS exactly.
  return [
    formatReceivedAt_(new Date()), // server receipt time
    formatScanTimestamp_(timestamp), // client-reported time, UTC
    appVersion
  ];
}

function formatReceivedAt_(date) {
  return Utilities.formatDate(date, RECEIVED_TIME_ZONE, 'yyyy-MM-dd HH:mm:ss');
}

function formatScanTimestamp_(timestamp) {
  return timestamp.replace('T', ' ');
}

function appendTelemetryRow_(sheet, row) {
  const nextRow = sheet.getLastRow() + 1;
  // Sheets otherwise recognises ISO-looking strings as dates and silently
  // replaces the requested display format with the spreadsheet locale's
  // format. Adjust the column range below if you add/remove timestamp
  // columns from HEADERS.
  sheet.getRange(nextRow, 1, 1, 2).setNumberFormat('@');
  sheet.getRange(nextRow, 1, 1, row.length).setValues([row]);
}

// Comma-separated codes in, comma-separated codes out - anything not in
// KNOWN_ERROR_CODES is dropped silently rather than stored, so a future
// unrecognised code (a bug, or a tampered payload) can never introduce free
// text into the sheet even if the driver-side validation is ever bypassed.
// Only relevant if your payload has an error-code field - remove otherwise.
function sanitiseErrorCodes_(value) {
  if (value === null || value === undefined || value === '') return '';
  return String(value)
    .split(',')
    .map(function (code) { return code.trim(); })
    .filter(function (code) { return KNOWN_ERROR_CODES.indexOf(code) !== -1; })
    .join(',');
}

function sanitiseString_(value) {
  if (value === null || value === undefined) return '';
  return String(value).replace(/[\r\n]/g, ' ').trim().slice(0, MAX_STRING_LENGTH);
}

function requireInt_(value, fieldName) {
  if (typeof value !== 'number' || !Number.isInteger(value) || value < 0 || value >= 1000000) {
    throw new Error(`Invalid ${fieldName}`);
  }
  return value;
}

function isConfigured_() {
  return SHEET_ID && SHEET_ID !== 'REPLACE_WITH_YOUR_SPREADSHEET_ID';
}

function getTelemetrySheet_() {
  const spreadsheet = SpreadsheetApp.openById(SHEET_ID);
  let sheet = spreadsheet.getSheetByName(SHEET_NAME);
  if (!sheet) sheet = spreadsheet.insertSheet(SHEET_NAME);
  if (sheet.getLastRow() === 0) sheet.appendRow(HEADERS);
  return sheet;
}

// Run this once from the Apps Script editor before deploying. It verifies the
// spreadsheet ID and creates the sheet tab and header row when required. Its
// presence in the function picker also confirms that the full file was
// pasted and saved, alongside doGet and doPost.
function setupTelemetrySheet() {
  if (!isConfigured_()) throw new Error('Replace SHEET_ID before running setup');
  const sheet = getTelemetrySheet_();
  return `Ready: ${sheet.getParent().getName()} / ${sheet.getName()}`;
}

function json_(obj) {
  return ContentService
    .createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}
