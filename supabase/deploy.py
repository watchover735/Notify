import os
import sys
import json
import urllib.request
import urllib.error
import ctypes
import ctypes.wintypes

advapi32 = ctypes.windll.advapi32

class CREDENTIAL(ctypes.Structure):
    _fields_ = [
        ('Flags', ctypes.wintypes.DWORD),
        ('Type', ctypes.wintypes.DWORD),
        ('TargetName', ctypes.wintypes.LPWSTR),
        ('Comment', ctypes.wintypes.LPWSTR),
        ('LastWritten', ctypes.wintypes.FILETIME),
        ('CredentialBlobSize', ctypes.wintypes.DWORD),
        ('CredentialBlob', ctypes.POINTER(ctypes.c_byte)),
        ('Persist', ctypes.wintypes.DWORD),
        ('AttributeCount', ctypes.wintypes.DWORD),
        ('Attributes', ctypes.c_void_p),
        ('TargetAlias', ctypes.wintypes.LPWSTR),
        ('UserName', ctypes.wintypes.LPWSTR),
    ]

def get_supabase_token():
    pcred = ctypes.POINTER(CREDENTIAL)()
    if advapi32.CredReadW('Supabase CLI:access-token', 1, 0, ctypes.byref(pcred)):
        blob = ctypes.string_at(pcred.contents.CredentialBlob, pcred.contents.CredentialBlobSize)
        token = blob.decode('utf-8', errors='ignore')
        advapi32.CredFree(pcred)
        return token
    raise RuntimeError("Supabase CLI access token not found in Windows Credential Manager")

def deploy_function(project_ref, slug, code, verify_jwt=False):
    token = get_supabase_token()
    headers = {
        'Authorization': f'Bearer {token}',
        'Content-Type': 'application/json'
    }

    # First check if function already exists
    get_url = f'https://api.supabase.com/v1/projects/{project_ref}/functions/{slug}'
    req = urllib.request.Request(get_url, headers=headers)
    exists = False
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            if resp.status == 200:
                exists = True
    except urllib.error.HTTPError as e:
        if e.code == 404:
            exists = False
        else:
            raise

    if exists:
        print(f"Updating existing function: {slug}...")
        url = f'https://api.supabase.com/v1/projects/{project_ref}/functions/{slug}'
        payload = json.dumps({'body': code, 'verify_jwt': verify_jwt}).encode('utf-8')
        req = urllib.request.Request(url, data=payload, headers=headers, method='PATCH')
    else:
        print(f"Creating new function: {slug}...")
        url = f'https://api.supabase.com/v1/projects/{project_ref}/functions'
        payload = json.dumps({'slug': slug, 'name': slug, 'body': code, 'verify_jwt': verify_jwt}).encode('utf-8')
        req = urllib.request.Request(url, data=payload, headers=headers, method='POST')

    with urllib.request.urlopen(req, timeout=30) as resp:
        result = json.loads(resp.read().decode('utf-8'))
        print(f"Successfully deployed {slug}: status={result.get('status', 'OK')}, version={result.get('version')}")
        return result

if __name__ == '__main__':
    project_ref = sys.argv[1] if len(sys.argv) > 1 else 'pnwoccbrpcihjhjmujfb'
    slug = sys.argv[2] if len(sys.argv) > 2 else 'test-fn'
    code_path = sys.argv[3] if len(sys.argv) > 3 else None
    
    if code_path and os.path.exists(code_path):
        with open(code_path, 'r', encoding='utf-8') as f:
            code = f.read()
    else:
        code = '''import { serve } from "https://deno.land/std@0.168.0/http/server.ts";

serve(async (req: Request) => {
  return new Response(JSON.stringify({ status: "ok", timestamp: Date.now() }), {
    headers: { "Content-Type": "application/json" }
  });
});'''
    deploy_function(project_ref, slug, code)
