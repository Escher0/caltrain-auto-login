// SPDX-FileCopyrightText: 2026 Brian Pilnick
// SPDX-License-Identifier: GPL-3.0-or-later
// Additional WebView2 permission: see LICENSING.md at the repository root.

//! Release-time verification using the same signature format as Tauri's updater.
use base64::{engine::general_purpose::STANDARD, Engine};
use minisign_verify::{PublicKey, Signature};
use std::{env, error::Error, fs};

fn main() -> Result<(), Box<dyn Error>> {
    let args: Vec<_> = env::args().skip(1).collect();
    if args.len() != 4 {
        return Err("Usage: verify-update INSTALLER SIGNATURE PUBLIC_KEY VERSION".into());
    }
    let decode_file = |path| -> Result<String, Box<dyn Error>> {
        Ok(String::from_utf8(
            STANDARD.decode(fs::read_to_string(path)?.trim())?,
        )?)
    };
    let key = PublicKey::decode(&decode_file(&args[2])?)?;
    let signature = Signature::decode(&decode_file(&args[1])?)?;
    key.verify(&fs::read(&args[0])?, &signature, true)?;
    let version = signature
        .trusted_comment()
        .split('\t')
        .find_map(|part| part.strip_prefix("version:"));
    if version != Some(args[3].as_str()) {
        return Err("Signature does not bind this release version".into());
    }
    Ok(())
}
