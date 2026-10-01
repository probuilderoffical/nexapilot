const SUPABASE_URL = 'https://cdwcvmeruzjhjcahehqg.supabase.co';
const SUPABASE_PUBLISHABLE_KEY = 'sb_publishable_EPJE4UJ1EcsGuf2uZxvmyg_fc3QJU2F';
const supabaseClient = supabase.createClient(SUPABASE_URL, SUPABASE_PUBLISHABLE_KEY);

const authCard = document.getElementById('authCard');
const appCard = document.getElementById('appCard');
const authStatus = document.getElementById('authStatus');
const runStatus = document.getElementById('runStatus');
const planOutput = document.getElementById('planOutput');
const devicesEl = document.getElementById('devices');
const userLabel = document.getElementById('userLabel');

function showAuth(message = '') {
  authCard.classList.remove('hidden');
  appCard.classList.add('hidden');
  authStatus.textContent = message;
}

async function showApp(session) {
  authCard.classList.add('hidden');
  appCard.classList.remove('hidden');
  userLabel.textContent = session.user.email || session.user.id;
  await loadDevices();
}

async function loadDevices() {
  const { data, error } = await supabaseClient
    .from('devices')
    .select('id,name,platform,os_version,status,last_seen_at')
    .order('created_at', { ascending: false });

  if (error) {
    devicesEl.textContent = `Could not load devices: ${error.message}`;
    return;
  }

  if (!data?.length) {
    devicesEl.textContent = 'No devices registered yet.';
    return;
  }

  devicesEl.innerHTML = data.map(d => `
    <div class="device-row">
      <strong>${escapeHtml(d.name)}</strong>
      <span>${escapeHtml(d.platform)} ${escapeHtml(d.os_version || '')}</span>
      <span>${escapeHtml(d.status)}</span>
    </div>
  `).join('');
}

function escapeHtml(value) {
  return String(value ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#039;');
}

document.getElementById('signUpBtn').addEventListener('click', async () => {
  authStatus.textContent = 'Creating account...';
  const email = document.getElementById('email').value.trim();
  const password = document.getElementById('password').value;
  const { error } = await supabaseClient.auth.signUp({ email, password });
  authStatus.textContent = error ? error.message : 'Account created. Check email if confirmation is enabled.';
});

document.getElementById('signInBtn').addEventListener('click', async () => {
  authStatus.textContent = 'Signing in...';
  const email = document.getElementById('email').value.trim();
  const password = document.getElementById('password').value;
  const { data, error } = await supabaseClient.auth.signInWithPassword({ email, password });
  if (error) {
    authStatus.textContent = error.message;
    return;
  }
  await showApp(data.session);
});

document.getElementById('signOutBtn').addEventListener('click', async () => {
  await supabaseClient.auth.signOut();
  showAuth('Signed out.');
});

document.getElementById('runBtn').addEventListener('click', async () => {
  const message = document.getElementById('command').value.trim();
  const target = document.getElementById('target').value;
  if (!message) return;

  runStatus.textContent = 'Thinking...';
  planOutput.textContent = '';

  const { data, error } = await supabaseClient.functions.invoke('ai-command', {
    body: { message, target }
  });

  if (error) {
    runStatus.textContent = `Error: ${error.message}`;
    return;
  }

  runStatus.textContent = `Model: ${data.model || 'Gemini'}`;
  planOutput.textContent = JSON.stringify(data.plan, null, 2);
});

supabaseClient.auth.getSession().then(({ data }) => {
  if (data.session) showApp(data.session);
  else showAuth();
});

supabaseClient.auth.onAuthStateChange((_event, session) => {
  if (session) showApp(session);
  else showAuth();
});
