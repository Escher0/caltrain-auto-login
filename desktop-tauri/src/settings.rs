use serde::{Deserialize, Serialize};
use std::{
    fs,
    io::{self, Write},
    path::{Path, PathBuf},
};

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct Settings {
    pub consent: bool,
    pub automatic: bool,
    pub check_updates: bool,
}
impl Default for Settings {
    fn default() -> Self {
        Self {
            consent: false,
            automatic: false,
            check_updates: true,
        }
    }
}
impl Settings {
    pub fn normalized(mut self) -> Self {
        if !self.consent {
            self.automatic = false;
        }
        self
    }
}
pub struct Store {
    path: PathBuf,
}
impl Store {
    pub fn new(directory: &Path) -> Self {
        Self {
            path: directory.join("settings.json"),
        }
    }
    pub fn load(&self) -> io::Result<Settings> {
        match fs::read(&self.path) {
            Ok(bytes) => {
                // Derived serde structs also accept positional arrays. Saved
                // consent must instead be an explicitly named object field.
                let value: serde_json::Value = serde_json::from_slice(&bytes)?;
                if !value.is_object() {
                    return Err(io::Error::other("Settings must be a JSON object"));
                }
                // Decode the original bytes so duplicate fields stay errors.
                serde_json::from_slice::<Settings>(&bytes)
                    .map(Settings::normalized)
                    .map_err(io::Error::other)
            }
            Err(e) if e.kind() == io::ErrorKind::NotFound => Ok(Settings::default()),
            Err(e) => Err(e),
        }
    }
    pub fn save(&self, settings: &Settings) -> io::Result<()> {
        let parent = self
            .path
            .parent()
            .ok_or_else(|| io::Error::other("Missing settings directory"))?;
        fs::create_dir_all(parent)?;
        let mut temporary = tempfile::NamedTempFile::new_in(parent)?;
        serde_json::to_writer_pretty(&mut temporary, &settings.clone().normalized())?;
        temporary.write_all(b"\n")?;
        temporary.as_file().sync_all()?;
        temporary.persist(&self.path).map_err(|e| e.error)?;
        Ok(())
    }
}
